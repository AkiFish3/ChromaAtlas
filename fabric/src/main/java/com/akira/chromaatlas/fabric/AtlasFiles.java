package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.AtlasConfig;
import com.akira.chromaatlas.core.ColorTable;
import com.akira.chromaatlas.core.TransparencyIndex;
import com.akira.chromaatlas.core.VanillaMapColors;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code config/chromaatlas/} 下三个文件的读写：{@code chromaatlas.json}（策略）、
 * {@code mapcolors.json}（自动色表）、{@code overrides.json}（手工覆盖）。
 *
 * <p>★容错口径★ 配置文件坏了不能让游戏起不来：任何解析失败都退回默认值并打一条 info，
 * 同时把坏文件改名 {@code *.bad} 留证。但<b>值非法</b>（色号越界、未知 id）不走静默 ——
 * 那是 M8 的显式报错范围。
 */
public final class AtlasFiles {

    public static final String DIR = "chromaatlas";

    private AtlasFiles() {
    }

    public static Path dir(Path gameDir) {
        return gameDir.resolve("config").resolve(DIR);
    }

    /** M-17：学习结果的落盘位置（{@code config/chromaatlas/learned_overrides.json}）。 */
    public static Path learnedFile(Path gameDir) {
        return dir(gameDir).resolve(com.akira.chromaatlas.core.LearnedExport.FILE_NAME);
    }

    /** 读策略；文件不存在时写一份默认值出去（这样玩家能看见有哪些开关）。 */
    public static AtlasConfig loadConfig(Path gameDir, Logger log) {
        AtlasConfig cfg = new AtlasConfig();
        Path f = dir(gameDir).resolve("chromaatlas.json");
        if (!Files.isRegularFile(f)) {
            writeDefaultConfig(f, log);
            return cfg;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            if (!e.isJsonObject()) {
                return cfg;
            }
            JsonObject o = e.getAsJsonObject();
            cfg.onlyFillNone = bool(o, "onlyFillNone", cfg.onlyFillNone);
            cfg.dryRun = bool(o, "dryRun", cfg.dryRun);
            if (o.has("colorStrategy") && o.get("colorStrategy").isJsonPrimitive()) {
                String v = o.get("colorStrategy").getAsString().trim();
                // ★M8 显式报错★ 未知策略不能静默当成 fill-none —— 玩家会以为配置生效了
                if (!v.equals("fill-none") && !v.equals("fill-missing-model") && !v.equals("force")
                        && !v.equals("auto")) {
                    log.info("[ChromaAtlas] colorStrategy='{}' 非法（只认 fill-none / fill-missing-model / force / auto），按 fill-none 处理", v);
                    v = "fill-none";
                }
                cfg.colorStrategy = v;
            }
            cfg.protectVanilla = bool(o, "protectVanilla", cfg.protectVanilla);
            cfg.runtimeFallback = bool(o, "runtimeFallback", cfg.runtimeFallback);
            cfg.itemFallback = bool(o, "itemFallback", cfg.itemFallback);
            cfg.mixinGetMapColor = bool(o, "mixinGetMapColor", cfg.mixinGetMapColor);
            cfg.skipTransparent = bool(o, "skipTransparent", cfg.skipTransparent);
            cfg.verbose = bool(o, "verbose", cfg.verbose);
            cfg.fallbackBatchSize = intOf(o, "fallbackBatchSize", cfg.fallbackBatchSize);
            cfg.fallbackSleepMs = intOf(o, "fallbackSleepMs", cfg.fallbackSleepMs);
            if (o.has("repaintPolicy") && o.get("repaintPolicy").isJsonPrimitive()) {
                String v = o.get("repaintPolicy").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!v.equals("off") && !v.equals("near") && !v.equals("all")) {
                    log.info("[ChromaAtlas] repaintPolicy='{}' 非法（只认 off/near/all），按 off 处理", v);
                    v = "off";
                }
                cfg.repaintPolicy = v;
            }
            cfg.repaintPixelsPerTick = intOf(o, "repaintPixelsPerTick", cfg.repaintPixelsPerTick);
            cfg.repaintRadiusChunks = intOf(o, "repaintRadiusChunks", cfg.repaintRadiusChunks);
            if (o.has("runtimeCacheFile") && o.get("runtimeCacheFile").isJsonPrimitive()) {
                cfg.runtimeCacheFile = o.get("runtimeCacheFile").getAsString();
            }
            readSet(o, "enabledNamespaces", cfg.enabledNamespaces);
            readSet(o, "disabledNamespaces", cfg.disabledNamespaces);
            // ★M-19★ 老配置缺键补写（只加不改）——必须放在值都读完以后，缺键这一轮仍走默认值
            backfillConfig(f, o, log);
        } catch (Exception ex) {
            log.info("[ChromaAtlas] chromaatlas.json 解析失败，使用默认值：{}", ex.toString());
            quarantine(f, log);
        }
        return cfg;
    }

    /** 已知键 + 出厂默认值（唯一来源：默认文件与缺键补写都吃这张表）。 */
    private static JsonObject knownDefaults() {
        JsonObject o = new JsonObject();
        o.addProperty("onlyFillNone", true);
        o.addProperty("dryRun", false);
        o.addProperty("colorStrategy", "fill-none");
        o.addProperty("runtimeFallback", true);
        o.addProperty("itemFallback", true);
        o.addProperty("mixinGetMapColor", true);
        o.addProperty("skipTransparent", true);
        o.addProperty("protectVanilla", true);
        o.addProperty("verbose", false);
        o.addProperty("fallbackBatchSize", 64);
        o.addProperty("fallbackSleepMs", 20);
        o.addProperty("repaintPolicy", "off");
        o.addProperty("repaintPixelsPerTick", 4096);
        o.addProperty("repaintRadiusChunks", 8);
        o.addProperty("runtimeCacheFile", "chromaatlas/cache.json");
        o.add("enabledNamespaces", new com.google.gson.JsonArray());
        o.add("disabledNamespaces", new com.google.gson.JsonArray());
        return o;
    }

    /**
     * ★M-19★ 老配置缺新键 ⇒ 补写回文件：<b>只加不改、保留用户值、保持原键序</b>。
     *
     * <p>缺键时代码本来就走默认值，<b>行为是对的</b>；问题是玩家在文件里既看不到也调不到这几个开关
     * （第 43 轮在两台实例上都发现缺 {@code itemFallback} / {@code protectVanilla}）。
     * 只在解析成功时调用；写不进去（只读配置目录）也不影响运行，只打一条 info。
     */
    static int backfillConfig(Path f, JsonObject parsed, Logger log) {
        JsonObject defaults = knownDefaults();
        int added = 0;
        for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
            if (!parsed.has(e.getKey())) {
                parsed.add(e.getKey(), e.getValue().deepCopy());
                added++;
            }
        }
        if (added == 0) {
            return 0;
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(parsed, w);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[ChromaAtlas] 配置缺 {} 个新键，已补写回 {}（只加不改，已有值未动）", added, f);
        } catch (Exception ex) {
            log.info("[ChromaAtlas] 配置缺 {} 个新键，补写失败（用默认值，行为不受影响）：{}", added, ex.toString());
        }
        return added;
    }

    private static void writeDefaultConfig(Path f, Logger log) {
        try {
            Files.createDirectories(f.getParent());
            JsonObject o = knownDefaults();
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
            log.info("[ChromaAtlas] 已生成默认配置 {}", f);
        } catch (Exception e) {
            log.info("[ChromaAtlas] 默认配置写入失败：{}", e.toString());
        }
    }

    /**
     * 读自动色表。支持两种值形态：{@code 13} 与 {@code {"c":13,"m":1}}。
     * {@code c<=0} 的条目<b>不</b>装进表（= 视为「表里没有」，交给运行时兜底再试一次）。
     */
    public static ColorTable loadTable(Path gameDir, Logger log) {
        ColorTable t = new ColorTable();
        // ★R44★ 先吃 jar 内出厂表（§1.2 第 3 层）。之前只读 config，
        // 结果发布出来的 jar 里根本没有色表 ⇒ 玩家装上去等于只开兜底。
        int bundled = loadBundledTable(t, log);
        Path f = dir(gameDir).resolve("mapcolors.json");
        if (!Files.isRegularFile(f)) {
            log.info("[ChromaAtlas] 未找到 {}（只用出厂表 {} 条 + 运行时兜底）", f, bundled);
            return t;
        }
        long t0 = System.nanoTime();
        int skipped = 0;
        int badColor = 0;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            int[] st = parseIntoT(t, JsonParser.parseReader(r));
            skipped = st[0];
            badColor = st[1];
            log.info("[ChromaAtlas] mapcolors.json 载入 {} 条（出厂表 {} 条；跳过无色 {} 条，其中色号非法 {} 条，{}ms）",
                    t.autoView().size(), bundled, skipped, badColor, (System.nanoTime() - t0) / 1_000_000L);
            if (badColor > 0) {
                log.info("[ChromaAtlas] ★配置错误★ mapcolors.json 有 {} 条色号越界（合法范围 1..{}，0 表示无色），这些条目已被忽略",
                        badColor, VanillaMapColors.MAX_ID);
            }
        } catch (Exception ex) {
            log.info("[ChromaAtlas] mapcolors.json 解析失败：{}", ex.toString());
            quarantine(f, log);
        }
        return t;
    }

    /**
     * M-8：读透明方块清单。两处来源，后读的覆盖前面的（同一个 Set，去重即可）：
     * <ol>
     *   <li>jar 内 {@code /chromaatlas/transparent_ids.json}（出厂清单，离线生成）</li>
     *   <li>{@code config/chromaatlas/transparent.json}（玩家追加自己的；同一格式）</li>
     * </ol>
     * 两份都可以是 {@code {"ids":[...]}} 或裸数组。坏文件只打 info 不影响启动。
     */
    public static TransparencyIndex loadTransparency(Path gameDir, Logger log) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        int bundled = 0;
        try (java.io.InputStream in = AtlasFiles.class.getResourceAsStream("/chromaatlas/transparent_ids.json")) {
            if (in != null) {
                try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                    bundled = readIdList(JsonParser.parseReader(r), ids);
                }
            } else {
                log.info("[ChromaAtlas] jar 内无透明清单（/chromaatlas/transparent_ids.json）");
            }
        } catch (Exception ex) {
            log.info("[ChromaAtlas] 出厂透明清单解析失败：{}", ex.toString());
        }
        int own = 0;
        Path f = dir(gameDir).resolve("transparent.json");
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                own = readIdList(JsonParser.parseReader(r), ids);
            } catch (Exception ex) {
                log.info("[ChromaAtlas] transparent.json 解析失败（忽略）：{}", ex.toString());
            }
        }
        if (bundled > 0 || own > 0) {
            log.info("[ChromaAtlas] 透明方块清单：{} 条（出厂 {} / 配置 {}）⇒ 这些 id 不补色",
                    ids.size(), bundled, own);
        }
        return TransparencyIndex.of(ids);
    }

    private static int readIdList(JsonElement root, java.util.Set<String> into) {
        if (root == null) {
            return 0;
        }
        JsonElement arr = root;
        if (root.isJsonObject() && root.getAsJsonObject().has("ids")) {
            arr = root.getAsJsonObject().get("ids");
        }
        if (arr == null || !arr.isJsonArray()) {
            return 0;
        }
        int n = 0;
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e != null && e.isJsonPrimitive()) {
                String s = e.getAsString().trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    into.add(s);
                    n++;
                }
            }
        }
        return n;
    }

    /** 出厂表：jar 内 {@code /chromaatlas/mapcolors.json}，缺失时静默跳过（老版本 jar / 裁剪版）。 */
    private static int loadBundledTable(ColorTable t, Logger log) {
        long t0 = System.nanoTime();
        try (java.io.InputStream in = AtlasFiles.class.getResourceAsStream("/chromaatlas/mapcolors.json")) {
            if (in == null) {
                log.info("[ChromaAtlas] jar 内无出厂色表（/chromaatlas/mapcolors.json），只靠 config + 兜底");
                return 0;
            }
            try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                int[] st = parseIntoT(t, JsonParser.parseReader(r));
                log.info("[ChromaAtlas] 出厂表载入 {} 条（跳过无色 {} 条，{}ms）",
                        t.autoView().size(), st[0], (System.nanoTime() - t0) / 1_000_000L);
            }
        } catch (Exception ex) {
            log.info("[ChromaAtlas] 出厂表解析失败：{}", ex.toString());
        }
        return t.autoView().size();
    }

    /** 把一段色表 json 灌进 {@code t}，返回 {@code {无色条数, 其中色号越界条数}}。config 表在后调用 ⇒ 同 id 覆盖出厂值。 */
    private static int[] parseIntoT(ColorTable t, JsonElement root) {
        int skipped = 0;
        int badColor = 0;
        if (root == null || !root.isJsonObject()) {
            return new int[]{0, 0};
        }
        for (Map.Entry<String, JsonElement> nsEntry : root.getAsJsonObject().entrySet()) {
            if (!nsEntry.getValue().isJsonObject()) {
                continue;
            }
            String ns = nsEntry.getKey();
            for (Map.Entry<String, JsonElement> e : nsEntry.getValue().getAsJsonObject().entrySet()) {
                int c = colorOf(e.getValue());
                String id = ns + ":" + e.getKey();
                if (VanillaMapColors.exists(c)) {
                    t.putAuto(id, c);
                } else {
                    skipped++;
                    if (c != -1) {
                        badColor++;   // 有值但越界（0 / 62+）：坏数据，不是「算不出色」
                    }
                }
                // m=0 无论 c 是否有效都要记：它是 fill-missing-model 策略的判据
                if (hasModel(e.getValue()) == 0) {
                    t.putNoModel(id);
                }
            }
        }
        return new int[]{skipped, badColor};
    }

    /** 读手工覆盖表 {@code {"ns:id":13}}；优先级永远最高。 */
    public static void loadOverrides(Path gameDir, ColorTable table, Logger log) {
        Path f = dir(gameDir).resolve("overrides.json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        int bad = 0;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                int c = colorOf(e.getValue());
                if (VanillaMapColors.exists(c)) {
                    table.putManual(e.getKey(), c);
                } else {
                    bad++;
                    log.info("[ChromaAtlas] ★配置错误★ overrides.json 非法值：{}={}（必须是 1..{}；0 = 无色，不能手工指定）",
                            e.getKey(), e.getValue(), VanillaMapColors.MAX_ID);
                }
            }
            log.info("[ChromaAtlas] overrides.json 载入 {} 条（非法 {} 条，未知 id 在注册表扫描后校验）",
                    table.manualView().size(), bad);
        } catch (Exception ex) {
            log.info("[ChromaAtlas] overrides.json 解析失败：{}", ex.toString());
            quarantine(f, log);
        }
    }

    /**
     * M-17：读学习结果 {@code learned_overrides.json}。
     *
     * <p>两种形态都吃：本 mod 自己导出的 {@code {"entries":{...}}}，以及裸的 {@code {"ns:id":13}}
     * （方便人手工从导出文件里裁几条贴进 overrides.json 之前先试）。
     * 元信息键（{@code //} / {@code from} / {@code code} / {@code count}）按
     * {@link com.akira.chromaatlas.core.LearnedExport#isMetaKey} 跳过 —— 这些键不是方块 id，
     * 当成 id 会打一片「配置错误」假红。
     *
     * @return 载入条数
     */
    public static int loadLearned(Path gameDir, ColorTable table, Logger log) {
        Path f = learnedFile(gameDir);
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        java.util.Map<String, Integer> entries = new java.util.LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (root == null || !root.isJsonObject()) {
                log.info("[ChromaAtlas] learned_overrides.json 不是对象，忽略：{}", f);
                return 0;
            }
            JsonElement body = root;
            if (root.getAsJsonObject().has("entries") && root.getAsJsonObject().get("entries").isJsonObject()) {
                body = root.getAsJsonObject().get("entries");
            }
            for (Map.Entry<String, JsonElement> e : body.getAsJsonObject().entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                if (com.akira.chromaatlas.core.LearnedExport.isMetaKey(k)) {
                    continue;
                }
                entries.put(k, colorOf(e.getValue()));
            }
        } catch (Exception ex) {
            log.info("[ChromaAtlas] learned_overrides.json 解析失败（忽略）：{}", ex.toString());
            return 0;
        }
        int[] st = com.akira.chromaatlas.core.LearnedExport.applyTo(entries, table);
        if (st[1] > 0) {
            log.info("[ChromaAtlas] ★配置错误★ learned_overrides.json 有 {} 条非法（id 非 ns:path 或色号不在 1..{}）",
                    st[1], VanillaMapColors.MAX_ID);
        }
        log.info("[ChromaAtlas] learned_overrides.json 载入 {} 条（{}；文件 {}）", st[0], f.getFileName(), f);
        return st[0];
    }

    /** M3 的 {@code m} 字段：1=有模型，0=无模型；缺失按 1 处理（保守，不乱补）。 */
    private static int hasModel(JsonElement v) {
        try {
            if (v.isJsonObject() && v.getAsJsonObject().has("m")) {
                return v.getAsJsonObject().get("m").getAsInt();
            }
        } catch (Exception ignored) {
            // 落到 1
        }
        return 1;
    }

    private static int colorOf(JsonElement v) {
        try {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                return v.getAsInt();
            }
            if (v.isJsonObject() && v.getAsJsonObject().has("c")) {
                return v.getAsJsonObject().get("c").getAsInt();
            }
        } catch (Exception ignored) {
            // 落到下面返回 -1
        }
        return -1;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOf(JsonObject o, String k, int def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static void readSet(JsonObject o, String k, java.util.Set<String> dst) {
        if (!o.has(k) || !o.get(k).isJsonArray()) {
            return;
        }
        for (JsonElement e : o.getAsJsonArray(k)) {
            if (e.isJsonPrimitive()) {
                dst.add(e.getAsString());
            }
        }
    }

    private static void quarantine(Path f, Logger log) {
        try {
            Path bad = f.resolveSibling(f.getFileName() + ".bad");
            Files.move(f, bad, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[ChromaAtlas] 坏配置已改名 -> {}", bad);
        } catch (Exception ignored) {
            // 改名失败也无所谓，不影响运行
        }
    }
}
