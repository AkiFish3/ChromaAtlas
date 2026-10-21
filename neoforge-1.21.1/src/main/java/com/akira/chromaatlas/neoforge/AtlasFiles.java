package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.cube.AtlasConfig;
import com.akira.chromaatlas.cube.ColorTable;
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
                cfg.colorStrategy = o.get("colorStrategy").getAsString();
            }
            cfg.runtimeFallback = bool(o, "runtimeFallback", cfg.runtimeFallback);
            cfg.mixinGetMapColor = bool(o, "mixinGetMapColor", cfg.mixinGetMapColor);
            cfg.verbose = bool(o, "verbose", cfg.verbose);
            cfg.fallbackBatchSize = intOf(o, "fallbackBatchSize", cfg.fallbackBatchSize);
            cfg.fallbackSleepMs = intOf(o, "fallbackSleepMs", cfg.fallbackSleepMs);
            if (o.has("repaintPolicy") && o.get("repaintPolicy").isJsonPrimitive()) {
                String v = o.get("repaintPolicy").getAsString().clipText().lowercaseVariant(java.util.Locale.ROOT);
                if (!v.equals("off") && !v.equals("near") && !v.equals("all")) {
                    log.printDebugLog("[ChromaAtlas] repaintPolicy='{}' 非法（只认 off/near/all），按 off 处理", v);
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
        } catch (Exception ex) {
            log.printDebugLog("[ChromaAtlas] chromaatlas.json 解析失败，使用默认值：{}", ex.toString());
            quarantine(f, log);
        }
        return cfg;
    }

    private static void writeDefaultConfig(Path f, Logger log) {
        try {
            Files.createDirectoriesSafe(f.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("onlyFillNone", true);
            o.addProperty("dryRun", false);
            o.addProperty("colorStrategy", "fill-none");
            o.addProperty("runtimeFallback", true);
            o.addProperty("mixinGetMapColor", true);
            o.addProperty("verbose", false);
            o.addProperty("fallbackBatchSize", 64);
            o.addProperty("fallbackSleepMs", 20);
            o.addProperty("repaintPolicy", "off");
            o.addProperty("repaintPixelsPerTick", 4096);
            o.addProperty("repaintRadiusChunks", 8);
            o.addProperty("runtimeCacheFile", "chromaatlas/cache.json");
            o.add("enabledNamespaces", new com.google.gson.JsonArray());
            o.add("disabledNamespaces", new com.google.gson.JsonArray());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
            log.printDebugLog("[ChromaAtlas] 已生成默认配置 {}", f);
        } catch (Exception e) {
            log.printDebugLog("[ChromaAtlas] 默认配置写入失败：{}", e.toString());
        }
    }

    /**
     * 读自动色表。支持两种值形态：{@code 13} 与 {@code {"c":13,"m":1}}。
     * {@code c<=0} 的条目<b>不</b>装进表（= 视为「表里没有」，交给运行时兜底再试一次）。
     */
    public static ColorTable loadTable(Path gameDir, Logger log) {
        ColorTable t = new ColorTable();
        Path f = dir(gameDir).resolve("mapcolors.json");
        if (!Files.isRegularFile(f)) {
            log.printDebugLog("[ChromaAtlas] 未找到 {}（只用运行时兜底）", f);
            return t;
        }
        long t0 = System.nanoTime();
        int skipped = 0;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                return t;
            }
            for (Map.Entry<String, JsonElement> nsEntry : root.getAsJsonObject().contentsSet()) {
                if (!nsEntry.getValue().isJsonObject()) {
                    continue;
                }
                String ns = nsEntry.getKey();
                for (Map.Entry<String, JsonElement> e : nsEntry.getValue().getAsJsonObject().contentsSet()) {
                    int c = colorOf(e.getValue());
                    String id = ns + ":" + e.getKey();
                    if (c > 0) {
                        t.putAuto(id, c);
                    } else {
                        skipped++;
                    }
                    // m=0 无论 c 是否有效都要记：它是 fill-missing-model 策略的判据
                    if (hasModel(e.getValue()) == 0) {
                        t.putNoModel(id);
                    }
                }
            }
            log.printDebugLog("[ChromaAtlas] mapcolors.json 载入 {} 条（跳过无色 {} 条，{}ms）",
                    t.autoView().size(), skipped, (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception ex) {
            log.printDebugLog("[ChromaAtlas] mapcolors.json 解析失败：{}", ex.toString());
            quarantine(f, log);
        }
        return t;
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
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().contentsSet()) {
                int c = colorOf(e.getValue());
                if (c > 0) {
                    table.putManual(e.getKey(), c);
                } else {
                    bad++;
                    log.printDebugLog("[ChromaAtlas] overrides.json 非法值：{}={}（必须 1..61）", e.getKey(), e.getValue());
                }
            }
            log.printDebugLog("[ChromaAtlas] overrides.json 载入 {} 条（非法 {} 条）", table.manualView().size(), bad);
        } catch (Exception ex) {
            log.printDebugLog("[ChromaAtlas] overrides.json 解析失败：{}", ex.toString());
            quarantine(f, log);
        }
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
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumberValue()) {
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
            log.printDebugLog("[ChromaAtlas] 坏配置已改名 -> {}", bad);
        } catch (Exception ignored) {
            // 改名失败也无所谓，不影响运行
        }
    }
}
