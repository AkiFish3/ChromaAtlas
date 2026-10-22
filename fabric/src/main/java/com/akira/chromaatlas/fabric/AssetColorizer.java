package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.NearestMapColor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C 档（运行时兜底）：从 mod jar 的 {@code assets} 现场算出方块的地图色。
 *
 * <p>链路与离线工具 {@code _mc3_palette_build.py} 完全一致，保证「表里算出的色」和
 * 「运行时兜底算出的色」不会差一个色号：
 * <pre>blockstates/&lt;id&gt;.json → 首个变体的 model → parent 链（跨 ns）
 *      → textures 合并（子覆盖父）→ particle（退化到首个非 #var 值）
 *      → PNG 均值（跳过 alpha&lt;16 像素，每 4 像素采样）→ CIELAB 最近邻</pre>
 *
 * <p>★线程模型★ 本类<b>只在单个后台线程</b>用（{@link RuntimeFallback}），内部不做同步；
 * 不要在主线程或地图渲染里 new 它。FileSystem 句柄在 {@link #close()} 统一释放。
 *
 * <p>★不读 vanilla 贴图★ 原版方块全都有 mapColor，永远轮不到兜底；而 {@code minecraft:}
 * 的资源不在 mod 容器里（客户端在 jar、服务端根本没有）。parent 走到 {@code minecraft:}
 * 只当作链路终止，不算失败。
 *
 * <p>★M-13 失败可诊断★ {@link #colorOf} 的 -1 以前是黑盒（只报「算不出色」）。
 * 现在每次失败都写一个 {@link Fail} 原因，调用方可以按原因分类打印 —— M-14/M-16 做不做全看这个。
 * 分类粒度是刻意设计的：
 * <ul>
 *   <li>{@code NO_MODEL_FILE} = blockstates 指向的 model json 根本不存在（自定义 loader / OBJ /
 *       运行时注册模型的典型表现）⇒ 这才是 baked-quad 路线（M-16）的目标；</li>
 *   <li>{@code VANILLA_TEXTURE} = 链路走到 {@code minecraft:} 贴图，服务端读不到原版包
 *       ⇒ 客户端/资源包路线（M-15）的目标，不是缺资源；</li>
 *   <li>{@code ALL_TRANSPARENT} = 贴图确实存在但全透明 ⇒ 真·无解，别浪费时间。</li>
 * </ul>
 */
public final class AssetColorizer {

    /** 每 4 像素采样一次：实测比全图快 3~4 倍，均值偏差 &lt;1/255。 */
    private static final int SAMPLE_STEP = 4;

    /** parent 链最大深度，防资源包写出环。 */
    private static final int MAX_DEPTH = 16;

    /**
     * 算不出色的原因（M-13）。{@code NONE} 只出现在成功路径上。
     *
     * <p>★不要随手合并分类★ 每个值都对应一条不同的修复路线：
     * {@code NO_MODEL_FILE}→M-16 baked-quad；{@code VANILLA_TEXTURE}→M-15 资源包；
     * {@code NO_BLOCKSTATE}/{@code NO_MODEL}→M-14 物品模型兜底；{@code ALL_TRANSPARENT}→无解。
     */
    public enum Fail {
        /** 成功（或被算出色）。 */
        NONE,
        /** 该命名空间没有 mod 容器（不在 FabricLoader 里）⇒ 非 mod 资源，无从读取。 */
        NO_CONTAINER,
        /** {@code assets/<ns>/blockstates/<path>.json} 不存在。 */
        NO_BLOCKSTATE,
        /** blockstates json 存在但解析不出来（空文件 / 不是对象）。 */
        BAD_BLOCKSTATE,
        /** blockstates 里没有任何可用 model 值。 */
        NO_MODEL,
        /** blockstates 指向的 model json 不存在 ⇒ 模型是运行时注册的（custom loader / OBJ）。 */
        NO_MODEL_FILE,
        /** parent 链读了模型但没有一个字面贴图（全是 {@code #var} 或空 textures）。 */
        NO_TEXTURE_REF,
        /** 贴图落在 {@code minecraft:}，原版资源不在 mod 容器里。 */
        VANILLA_TEXTURE,
        /** 贴图路径不存在（上游拼错 / 资源缺失）。 */
        TEX_MISSING,
        /** PNG 解不开 / ImageIO 返回 null。 */
        TEX_DECODE_FAIL,
        /** 贴图存在但采样到的像素全是 alpha&lt;16。 */
        ALL_TRANSPARENT,
        /** 链路抛异常（兜底分类，正常不该出现）。 */
        ERROR
    }

    private final Logger log;
    private final Map<String, FileSystem> fsCache = new HashMap<>();
    /** 由我们自己 {@code newFileSystem} 打开、必须由我们关闭的 namespace（见 {@link #close()}）。 */
    private final Set<String> owned = new HashSet<>();
    /** 贴图 id → 色号；同一张贴图被几百个方块引用时只解一次。算不出也记 -1（见 {@link #texFail}）。 */
    private final Map<String, Integer> texColor = new HashMap<>();
    /** 贴图 id → 失败原因（配合 {@link #texColor} 的 -1，避免重复解码同一张坏贴图时丢原因）。 */
    private final Map<String, Fail> texFail = new HashMap<>();
    private int decoded;
    private int decodeFail;
    /** 最近一次 {@link #colorOf} 的失败原因；单线程使用，无需同步。 */
    private Fail lastFail = Fail.NONE;
    /** 贴图解码阶段的失败原因，由 {@link #meanColor} 写、由 {@link #colorOf} 转出去。 */
    private Fail meanFail = Fail.NONE;

    public AssetColorizer(Logger log) {
        this.log = log;
    }

    /** 诊断计数，跑完写日志。 */
    public int decodedTextures() {
        return decoded;
    }

    public int failedTextures() {
        return decodeFail;
    }

    /** 最近一次 {@link #colorOf} 失败的原因（成功时为 {@link Fail#NONE}）。 */
    public Fail lastFail() {
        return lastFail;
    }

    /**
     * @param blockId {@code namespace:path}
     * @return 1..61；算不出返回 -1（调用方记 info 后跳过，<b>不要当 0 用</b>）
     */
    public int colorOf(String blockId) {
        lastFail = Fail.NONE;
        String ns = ColorIds.ns(blockId);
        String path = ColorIds.path(blockId);
        try {
            FileSystem fs = fsOf(ns);
            if (fs == null) {
                return fail(Fail.NO_CONTAINER);
            }
            Path bsPath = fs.getPath("/assets/" + ns + "/blockstates/" + path + ".json");
            if (!Files.isRegularFile(bsPath)) {
                return fail(Fail.NO_BLOCKSTATE);
            }
            JsonObject bs = safeJson(bsPath);
            if (bs == null) {
                return fail(Fail.BAD_BLOCKSTATE);
            }
            String modelId = firstModel(bs);
            if (modelId == null) {
                return fail(Fail.NO_MODEL);
            }
            Chain chain = followParent(modelId);
            if (chain == null || chain.textures.isEmpty()) {
                // 一个模型 json 都没读到 ⇒ 模型是运行时注册的；读到了却没字面贴图 ⇒ 引用链问题
                return fail(chain != null && chain.models > 0 ? Fail.NO_TEXTURE_REF : Fail.NO_MODEL_FILE);
            }
            return colorOfCandidates(chain.textures);
        } catch (Throwable t) {
            return fail(Fail.ERROR);
        }
    }

    /**
     * 逐个候选贴图试到出颜色（M-13 的修法）。
     *
     * <p>★为什么不能只选一张★ 实测 1.20.1-TEST 的 23 条残余里，13 条的「particle」或「第一个字面
     * 贴图」正好是透明占位图 {@code coc:block/c}（8×8 全透明），真正有颜色的贴图排在
     * {@code textures} 的后面（garland 系列在 key {@code "0"}、chain 系列在 {@code middle1}）。
     * 单选必错、且错法是「全透明 ⇒ 放弃」，看起来跟「资源缺失」一模一样。
     *
     * <p>顺序见 {@link #pickTextures}：字面 particle → 其余按声明顺序；全程 {@code #var} 解引用。
     * 失败原因取**第一个非 TEX_MISSING** 的（缺图只是这一张缺，全透明才是这批的真性质）。
     */
    private int colorOfCandidates(List<String> candidates) {
        Fail firstReason = null;
        for (String tex : candidates) {
            Integer cached = texColor.get(tex);
            if (cached != null) {
                if (cached > 0) {
                    return cached;
                }
                Fail why = texFail.get(tex);
                if (firstReason == null || firstReason == Fail.TEX_MISSING) {
                    firstReason = why;
                }
                continue;
            }
            int rgb = meanColor(tex);
            if (rgb < 0) {
                texColor.put(tex, -1);
                texFail.put(tex, meanFail);
                if (firstReason == null || firstReason == Fail.TEX_MISSING) {
                    firstReason = meanFail;
                }
                continue;
            }
            int color = NearestMapColor.nearest(rgb);
            texColor.put(tex, color);
            return color;
        }
        return fail(firstReason == null ? Fail.NO_TEXTURE_REF : firstReason);
    }

    /** 记录失败原因并返回 -1，保持 {@link #colorOf} 的单出口语义。 */
    private int fail(Fail f) {
        lastFail = f == null ? Fail.ERROR : f;
        return -1;
    }

    public void close() {
        // ★只关自己 new 出来的 FileSystem★
        // jar 模组的 rootPath 是 **fabric 自己的 zipfs 里的路径**（Files.isDirectory(root) 为 true），
        // 我们只是借用了它的 FS。曾经无脑 close(fsCache.values()) ⇒ 把模组资源包的 FS 关掉，
        // 之后 ModNioResourcePack.exists 抛 ClosedFileSystemException，客户端在主菜单直接崩（R24）。
        for (Map.Entry<String, FileSystem> e : fsCache.entrySet()) {
            if (!owned.contains(e.getKey())) {
                continue;
            }
            try {
                e.getValue().close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }
        fsCache.clear();
        owned.clear();
        texColor.clear();
        texFail.clear();
    }

    // ---------------- jar / 资源 ----------------

    private FileSystem fsOf(String ns) throws Exception {
        if (fsCache.containsKey(ns)) {
            return fsCache.get(ns);
        }
        FileSystem fs = null;
        ModContainer mc = FabricLoader.getInstance().getModContainer(ns).orElse(null);
        if (mc != null && !mc.getRootPaths().isEmpty()) {
            Path root = mc.getRootPaths().get(0);
            if (Files.isDirectory(root)) {
                // fabric 已经替这个模组打开了 zipfs ⇒ 借用，**不接管所有权**
                fs = root.getFileSystem();
            } else {
                fs = FileSystems.newFileSystem(root);
                owned.add(ns);
            }
        }
        fsCache.put(ns, fs);
        return fs;
    }

    private static JsonObject safeJson(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstModel(JsonObject bs) {
        if (bs == null) {
            return null;
        }
        if (bs.has("variants") && bs.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : bs.getAsJsonObject("variants").entrySet()) {
                String m = modelOf(e.getValue());
                if (m != null) {
                    return m;
                }
            }
        }
        if (bs.has("multipart") && bs.get("multipart").isJsonArray()) {
            for (JsonElement el : bs.getAsJsonArray("multipart")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("apply")) {
                    String m = modelOf(el.getAsJsonObject().get("apply"));
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static String modelOf(JsonElement el) {
        if (el == null) {
            return null;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String m = modelOf(e);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
        if (el.isJsonObject() && el.getAsJsonObject().has("model")) {
            JsonElement m = el.getAsJsonObject().get("model");
            return m.isJsonPrimitive() ? m.getAsString() : null;
        }
        return null;
    }

    /** parent 链解析结果：合并后的 textures ＋ 是否有 elements ＋ 实际读到几个模型 json。 */
    private static final class Chain {
        /** 候选贴图（优先级从高到低），由 {@link #pickTextures} 生成。 */
        List<String> textures = java.util.Collections.emptyList();
        boolean hasElements;
        /** 读到并解析成功的 model json 个数；0 = 模型是运行时注册的（见 {@link Fail#NO_MODEL_FILE}）。 */
        int models;
    }

    private Chain followParent(String modelId) {
        Chain c = new Chain();
        // 子覆盖父 ⇒ 从头往下写，先到先得
        Map<String, String> textures = new LinkedHashMap<>();
        String cur = modelId;
        for (int depth = 0; depth < MAX_DEPTH && cur != null; depth++) {
            if (cur.startsWith("builtin/")) {
                break;
            }
            String ns = ColorIds.ns(cur);
            String path = ColorIds.path(cur);
            FileSystem fs;
            try {
                fs = fsOf(ns);
            } catch (Exception e) {
                break;
            }
            if (fs == null) {
                break; // minecraft: / 无容器 ⇒ 链路终止
            }
            Path mp = fs.getPath("/assets/" + ns + "/models/" + path + ".json");
            if (!Files.isRegularFile(mp)) {
                break;
            }
            JsonObject o = safeJson(mp);
            if (o == null) {
                break;
            }
            c.models++;
            if (!c.hasElements && o.has("elements")) {
                c.hasElements = true;
            }
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("textures").entrySet()) {
                    String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                    if (v != null && !textures.containsKey(e.getKey())) {
                        textures.put(e.getKey(), v);
                    }
                }
            }
            cur = o.has("parent") ? o.get("parent").getAsString() : null;
        }
        c.textures = pickTextures(textures);
        return c;
    }

    /**
     * 按优先级给出**全部**字面贴图，交给调用方逐个试。
     *
     * <p>顺序：字面 particle → 其余按声明顺序（子覆盖父，先到先得）。所有 {@code #var} 都解引用。
     *
     * <p>★不再「particle 优先、只取一个」★ 那是离线 {@code _mc3} 的口径，它把
     * {@code "particle": "#1"}（→ 透明占位图）当成结论，直接判「全透明」；
     * runtime 以前是「particle 只认字面值」，虽然躲过了这一例，但撞上
     * {@code minia:chain_*}/{@code minia:rope} 这种「第一个字面贴图也是占位图」照样错。
     */
    private static List<String> pickTextures(Map<String, String> textures) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String particle = textures.get("particle");
        if (particle != null) {
            String r = resolveVar(textures, particle);
            if (r != null && !seen.contains(r)) {
                out.add(r);
                seen.add(r);
            }
        }
        for (String v : textures.values()) {
            String r = resolveVar(textures, v);
            if (r != null && !seen.contains(r)) {
                out.add(r);
                seen.add(r);
            }
        }
        return out;
    }

    /**
     * {@code #var} 解引用（如 {@code "particle": "#1"} ⇒ {@code textures["1"]}）。
     *
     * @return 字面贴图 id；链断在 {@code #} 上返回 null
     */
    private static String resolveVar(Map<String, String> textures, String value) {
        String cur = value;
        for (int depth = 0; depth < 8 && cur != null && cur.startsWith("#"); depth++) {
            cur = textures.get(cur.substring(1));
        }
        if (cur == null || cur.startsWith("#")) {
            return null;
        }
        return cur;
    }

    /**
     * 贴图平均 RGB。
     *
     * @return 24bit RGB；失败返回 -1，原因写在 {@link #meanFail}
     */
    private int meanColor(String texId) {
        String ns = ColorIds.ns(texId);
        String path = ColorIds.path(texId);
        if ("minecraft".equals(ns)) {
            meanFail = Fail.VANILLA_TEXTURE;
            return -1; // 原版资源不在这里，见类注释
        }
        Path png;
        try {
            FileSystem fs = fsOf(ns);
            if (fs == null) {
                meanFail = Fail.NO_CONTAINER;
                return -1;
            }
            png = fs.getPath("/assets/" + ns + "/textures/" + path + ".png");
            if (!Files.isRegularFile(png)) {
                meanFail = Fail.TEX_MISSING;
                return -1;
            }
        } catch (Exception e) {
            meanFail = Fail.ERROR;
            return -1;
        }
        try (InputStream in = Files.newInputStream(png)) {
            BufferedImage img = ImageIO.read(in);
            decoded++;
            if (img == null) {
                decodeFail++;
                meanFail = Fail.TEX_DECODE_FAIL;
                return -1;
            }
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < img.getHeight(); y += SAMPLE_STEP) {
                for (int x = 0; x < img.getWidth(); x += SAMPLE_STEP) {
                    int argb = img.getRGB(x, y);
                    if (((argb >>> 24) & 0xFF) < 16) {
                        continue;
                    }
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                    n++;
                }
            }
            if (n == 0) {
                meanFail = Fail.ALL_TRANSPARENT;
                return -1;
            }
            meanFail = Fail.NONE;
            return ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        } catch (Exception e) {
            decoded++;
            decodeFail++;
            meanFail = Fail.TEX_DECODE_FAIL;
            return -1;
        }
    }

    /** {@code ns:path} 拆分，避免污染 core（core 的 key 语义同此）。 */
    static final class ColorIds {
        private ColorIds() {
        }

        static String ns(String id) {
            int i = id.indexOf(':');
            return i < 0 ? "minecraft" : id.substring(0, i);
        }

        static String path(String id) {
            int i = id.indexOf(':');
            return i < 0 ? id : id.substring(i + 1);
        }
    }
}
