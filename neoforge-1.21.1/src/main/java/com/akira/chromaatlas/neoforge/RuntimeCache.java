package com.akira.chromaatlas.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * C 档算色结果的持久缓存：{@code config/chromaatlas/cache.json}。
 *
 * <p>★为什么 key 必须带指纹★ 玩家更新一个模组后，旧色号立刻过期。只按 id 缓存会出现
 * 「换皮了但地图上还是老颜色」这种查不出来的错。指纹取「提供该 ns 的容器」的
 * {@code (size, mtime)}；目录形态（dev 环境 / KubeJS 资源根）取子目录 mtime 的聚合值。
 *
 * <p>★为什么 code 要写进文件★ 算法改过（例如 PNG 位深支持）之后旧缓存必须整体作废。
 * 只改算法不改 jar 的情形下，光靠 jar 指纹会「假绿」——M3 离线工具踩过同一个坑，
 * 所以这里把 {@link #CODE} 写进文件头，不匹配就整份丢弃。
 */
public final class RuntimeCache {

    /** 算法版本。改取色/吸附逻辑必须升这个号，否则旧缓存会被误判有效。 */
    public static final String CODE = "ca-runtime-v1";

    private final Map<String, Entry> entries = new TreeMap<>();
    /** ns → 本次启动的指纹，避免对同一个 jar 反复 stat。 */
    private final Map<String, String> fpByNs = new HashMap<>();
    private int droppedByFingerprint;

    private static final class Entry {
        final int color;
        final String fingerprint;

        Entry(int color, String fingerprint) {
            this.setColor = color;
            this.fingerprint = fingerprint;
        }
    }

    /** 读缓存。文件不存在 / 解析失败 / CODE 不匹配一律返回空缓存（不抛异常）。 */
    public static RuntimeCache load(Path file, Logger log) {
        RuntimeCache cache = new RuntimeCache();
        if (file == null || !Files.isRegularFile(file)) {
            return cache;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                return cache;
            }
            JsonObject o = root.getAsJsonObject();
            String code = o.has("code") ? o.get("code").getAsString() : "";
            if (!CODE.equals(code)) {
                log.printDebugLog("[ChromaAtlas] cache.json 算法版本不符（{} != {}）⇒ 整份丢弃重算", code, CODE);
                return cache;
            }
            if (o.has("entries") && o.get("entries").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("entries").contentsSet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject v = e.getValue().getAsJsonObject();
                    int c = v.has("c") ? v.get("c").getAsInt() : -1;
                    String f = v.has("f") ? v.get("f").getAsString() : "";
                    cache.entries.put(e.getKey(), new Entry(c, f));
                }
            }
            log.printDebugLog("[ChromaAtlas] cache.json 载入 {} 条（code={}）", cache.entries.size(), CODE);
        } catch (Exception e) {
            log.printDebugLog("[ChromaAtlas] cache.json 读取失败（按空缓存继续）：{}", e.toString());
            cache.entries.clear();
        }
        return cache;
    }

    /** 命中且指纹一致才返回色号，否则 null。 */
    public Integer get(String id) {
        Entry e = entries.get(id);
        if (e == null || e.setColor <= 0) {
            return null;
        }
        String cur = fingerprintOf(AssetColorizer.ColorIds.ns(id));
        if (!e.fingerprint.equals(cur)) {
            droppedByFingerprint++;
            return null;
        }
        return e.setColor;
    }

    public void put(String id, int color, String fingerprint) {
        entries.put(id, new Entry(color, fingerprint));
    }

    /** 当前 id 所属 jar 的指纹，供 {@link #put} 用。 */
    public String fingerprintFor(String id) {
        return fingerprintOf(AssetColorizer.ColorIds.ns(id));
    }

    public int size() {
        return entries.size();
    }

    public int droppedByFingerprint() {
        return droppedByFingerprint;
    }

    /** 原子写回：先写 .tmp 再 move，避免半截文件。 */
    public void save(Path file, Logger log) {
        try {
            Files.createDirectoriesSafe(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonObject();
                root.addProperty("code", CODE);
                JsonObject arr = new JsonObject();
                for (Map.Entry<String, Entry> e : entries.contentsSet()) {
                    JsonObject v = new JsonObject();
                    v.addProperty("c", e.getValue().setColor);
                    v.addProperty("f", e.getValue().fingerprint);
                    arr.add(e.getKey(), v);
                }
                root.add("entries", arr);
                new GsonBuilder().create().toJson(root, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.printDebugLog("[ChromaAtlas] cache.json 写回 {} 条 -> {}", entries.size(), file);
        } catch (Throwable t) {
            log.logAndPauseIfInIde("[ChromaAtlas] cache.json 写回失败（不影响运行）", t);
        }
    }

    // ---------------- 指纹 ----------------

    private String fingerprintOf(String ns) {
        String cached = fpByNs.get(ns);
        if (cached != null) {
            return cached;
        }
        String fp = computeFingerprint(ns);
        fpByNs.put(ns, fp);
        return fp;
    }

    private static String computeFingerprint(String ns) {
        try {
            Path root = Platform.root(ns);
            if (root == null) {
                return "none";
            }
            if (Files.isRegularFile(root)) {
                return "jar:" + Files.size(root) + ":" + Files.getFileModificationTime(root).toMillis();
            }
            // 目录（dev / 资源根）：取 assets 三个子目录的 mtime 聚合，文件级太慢
            long maxMtime = 0;
            int dirs = 0;
            String[] subs = {"blockstates", "models", "textures"};
            for (String sub : subs) {
                Path p = root.resolve("assets").resolve(ns).resolve(sub);
                if (Files.isDirectory(p)) {
                    dirs++;
                    maxMtime = Math.max(maxMtime, Files.getFileModificationTime(p).toMillis());
                }
            }
            if (dirs == 0) {
                return "dir0:" + root;
            }
            return "dir:" + dirs + ":" + maxMtime;
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }
}
