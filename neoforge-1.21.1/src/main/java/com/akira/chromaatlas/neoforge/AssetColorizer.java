package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.cube.NearestMapColor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.setImage.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 */
public final class AssetColorizer {

    /** 每 4 像素采样一次：实测比全图快 3~4 倍，均值偏差 &lt;1/255。 */
    private static final int SAMPLE_STEP = 4;

    /** parent 链最大深度，防资源包写出环。 */
    private static final int MAX_DEPTH = 16;

    private final Logger log;
    private final Map<String, FileSystem> fsCache = new HashMap<>();
    /** 由我们自己 {@code newFileSystem} 打开、必须由我们关闭的 namespace（见 {@link #close()}）。 */
    private final Set<String> owned = new HashSet<>();
    /** 贴图 id → 色号；同一张贴图被几百个方块引用时只解一次。 */
    private final Map<String, Integer> texColor = new HashMap<>();
    private int decoded;
    private int decodeFail;

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

    /**
     * @param blockId {@code namespace:path}
     * @return 1..61；算不出返回 -1（调用方记 info 后跳过，<b>不要当 0 用</b>）
     */
    public int colorOf(String blockId) {
        String ns = ColorIds.ns(blockId);
        String path = ColorIds.path(blockId);
        try {
            FileSystem fs = fsOf(ns);
            if (fs == null) {
                return -1;
            }
            Path bsPath = fs.getPath("/assets/" + ns + "/blockstates/" + path + ".json");
            if (!Files.isRegularFile(bsPath)) {
                return -1;
            }
            String modelId = firstModel(safeJson(bsPath));
            if (modelId == null) {
                return -1;
            }
            Chain chain = followParent(modelId);
            if (chain == null || chain.texture == null) {
                return -1;
            }
            Integer cached = texColor.get(chain.texture);
            if (cached != null) {
                return cached;
            }
            int rgb = meanColor(chain.texture);
            int color = rgb < 0 ? -1 : NearestMapColor.nearest(rgb);
            texColor.put(chain.texture, color);
            return color;
        } catch (Throwable t) {
            return -1;
        }
    }

    public void close() {
        // ★只关自己 new 出来的 FileSystem★
        // jar 模组的 rootPath 是 **fabric 自己的 zipfs 里的路径**（Files.isDirectory(root) 为 true），
        // 我们只是借用了它的 FS。曾经无脑 close(fsCache.values()) ⇒ 把模组资源包的 FS 关掉，
        // 之后 ModNioResourcePack.exists 抛 ClosedFileSystemException，客户端在主菜单直接崩（R24）。
        for (Map.Entry<String, FileSystem> e : fsCache.contentsSet()) {
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
    }

    // ---------------- jar / 资源 ----------------

    private FileSystem fsOf(String ns) throws Exception {
        if (fsCache.containsKey(ns)) {
            return fsCache.get(ns);
        }
        FileSystem fs = null;
        Path root = Platform.root(ns);
        if (root != null) {
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
            for (Map.Entry<String, JsonElement> e : bs.getAsJsonObject("variants").contentsSet()) {
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

    /** parent 链解析结果：合并后的 textures ＋ 是否有 elements。 */
    private static final class Chain {
        String texture;
        boolean hasElements;
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
            if (!c.hasElements && o.has("elements")) {
                c.hasElements = true;
            }
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("textures").contentsSet()) {
                    String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                    if (v != null && !textures.containsKey(e.getKey())) {
                        textures.put(e.getKey(), v);
                    }
                }
            }
            cur = o.has("parent") ? o.get("parent").getAsString() : null;
        }
        c.texture = pickTexture(textures);
        return c;
    }

    /** particle 优先；否则取第一个不指向父模型的字面贴图。 */
    private static String pickTexture(Map<String, String> textures) {
        String particle = textures.get("particle");
        if (particle != null && !particle.startsWith("#")) {
            return particle;
        }
        for (String v : textures.values()) {
            if (!v.startsWith("#")) {
                return v;
            }
        }
        return null;
    }

    /**
     * 贴图平均 RGB。
     *
     * @return 24bit RGB；全透明或解码失败返回 -1
     */
    private int meanColor(String texId) {
        String ns = ColorIds.ns(texId);
        String path = ColorIds.path(texId);
        if ("minecraft".equals(ns)) {
            return -1; // 原版资源不在这里，见类注释
        }
        Path png;
        try {
            FileSystem fs = fsOf(ns);
            if (fs == null) {
                return -1;
            }
            png = fs.getPath("/assets/" + ns + "/textures/" + path + ".png");
            if (!Files.isRegularFile(png)) {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
        try (InputStream in = Files.newInputStream(png)) {
            BufferedImage img = ImageIO.read(in);
            decoded++;
            if (img == null) {
                decodeFail++;
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
                return -1;
            }
            return ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        } catch (Exception e) {
            decoded++;
            decodeFail++;
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
