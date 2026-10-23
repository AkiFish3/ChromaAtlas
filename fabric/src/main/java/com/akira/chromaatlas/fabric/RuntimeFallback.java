package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.AtlasConfig;
import com.akira.chromaatlas.core.ColorSnapshot;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 档兜底线程：把「静态色表里没有、且当前 mapColor 为 NONE」的方块算出来并补进快照。
 *
 * <p>★为什么不是 per-tick 切片★ 切片要保证每 tick 只干 1~2ms，实现复杂且一旦超预算就
 * 污染 TPS。这里用 1 个 daemon 线程 + {@code MIN_PRIORITY+1} + 每批 sleep，把活挪出主线程，
 * 主线程完全不等待 ⇒ 启动耗时的增量为 0（验收 ① 的「&lt; 1s」口径）。
 *
 * <p>★读侧零成本★ 后台每算完一批调 {@link ColorSnapshot#mergeAuto} 整体换引用；
 * 未算完期间 {@code getMapColor} 命中不到就返回 -1 ⇒ 保持原版 NONE，不加锁、不阻塞。
 *
 * <p>★M-13：失败不再是黑盒★ 以前只有一句「有 N 个方块算不出色（无模型/贴图缺失/全透明）」，
 * 三条完全不同的修复路线挤在一起。现在按 {@link AssetColorizer.Fail} 分类计数，每个原因
 * 附最多 {@value #SAMPLE_PER_REASON} 个 id，并且能通过 {@code /chromaatlas status} 回看。
 */
public final class RuntimeFallback {

    /**
     * 每个失败原因最多打印/保留多少个 id。
     *
     * <p>★为什么是 40 而不是 10★ 实测 1.20.1-TEST 的残余失败正好 23 个（NO_CONTAINER 22 +
     * NO_TEXTURE_REF 1），第一版按 10 个截断 ⇒ 只看到 10 个 id，M-14/M-16 该不该做依然判断不了。
     * 残余集本来就是「表里没有 + NONE」的尾巴，量级是几十不是几万 ⇒ 一次打全比猜便宜。
     */
    private static final int SAMPLE_PER_REASON = 40;

    private static volatile boolean running;
    private static volatile int lastComputed = -1;
    private static volatile int lastReused = -1;
    private static volatile int lastFailed = -1;
    private static volatile long lastElapsedMs = -1;
    /** M-15：上一轮命中「客户端资源包」与「mod 容器」的读取次数。 */
    private static volatile int lastPackHits = -1;
    private static volatile int lastJarHits = -1;
    /** 上一轮的失败原因计数（不可变快照，给 status 用）。 */
    private static volatile Map<AssetColorizer.Fail, Integer> lastReasons = Collections.emptyMap();
    /** 上一轮每个失败原因的样例 id（不可变快照，给 status 用）。 */
    private static volatile Map<AssetColorizer.Fail, List<String>> lastSamples = Collections.emptyMap();
    /** M-14：上一轮「物品模型兜底救回」的方块 id（最多 {@value #SAMPLE_PER_REASON} 个）。 */
    private static volatile List<String> lastItemRescued = Collections.emptyList();
    /** M-14：上一轮试过/救回次数。 */
    private static volatile int lastItemTried = -1;
    private static volatile int lastItemHit = -1;

    private RuntimeFallback() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** 上一轮的统计（未跑过返回 -1），给 `/chromaatlas status` 用。 */
    public static String lastStats() {
        if (lastComputed < 0) {
            return "未运行";
        }
        String s = "computed=" + lastComputed + " reused=" + lastReused + " failed=" + lastFailed
                + " elapsed=" + lastElapsedMs + "ms"
                + " source=pack:" + lastPackHits + "/jar:" + lastJarHits;
        if (!AssetColorizer.hasPackSource()) {
            s += "（无资源包来源：服务端 或 尚未装）";
        }
        if (lastFailed > 0 && !lastReasons.isEmpty()) {
            s += " 原因={" + summaryOf(lastReasons) + "}";
        }
        if (lastItemTried >= 0) {
            s += " item=" + lastItemHit + "/" + lastItemTried;
        }
        return s;
    }

    /** M-14：上一轮被物品模型兜底救回的 id（只读）。 */
    public static List<String> lastItemRescued() {
        return lastItemRescued;
    }

    /** 上一轮的失败原因计数（只读，可能为空）。 */
    public static Map<AssetColorizer.Fail, Integer> lastReasons() {
        return lastReasons;
    }

    /** 上一轮每个失败原因的样例 id（只读，可能为空）。 */
    public static Map<AssetColorizer.Fail, List<String>> lastSamples() {
        return lastSamples;
    }

    /**
     * 启动兜底线程（非阻塞，立即返回）。
     *
     * @param candidates 需要算色的方块 id（{@code ns:path}），调用方已做过「表里没有 + NONE + 命名空间允许」过滤
     * @param snap       颜色快照，算完分批合并
     * @param cacheFile  {@code config/chromaatlas/cache.json}
     * @param dryRun     true ⇒ 照算、照写 cache，但<b>不</b>推进快照（= 不生效）
     */
    public static void start(List<String> candidates, AtlasConfig cfg, ColorSnapshot snap,
                             Path cacheFile, Logger log, boolean dryRun) {
        if (candidates == null || candidates.isEmpty()) {
            log.info("[ChromaAtlas/fallback] 无待补方块，跳过");
            return;
        }
        if (running) {
            log.info("[ChromaAtlas/fallback] 已在运行，忽略重复启动");
            return;
        }
        running = true;
        Thread t = new Thread(() -> run(candidates, cfg, snap, cacheFile, log, dryRun),
                "ChromaAtlas-fallback");
        t.setDaemon(true);
        // 让出 CPU：算色是纯背景活，绝不该跟主线程抢核
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private static void run(List<String> candidates, AtlasConfig cfg, ColorSnapshot snap,
                            Path cacheFile, Logger log, boolean dryRun) {
        long t0 = System.nanoTime();
        int computed = 0, reused = 0, failed = 0, batches = 0;
        AssetColorizer colorizer = new AssetColorizer(log, cfg.itemFallback);
        List<String> itemRescued = new ArrayList<>();
        RuntimeCache cache = RuntimeCache.load(cacheFile, log);
        Map<String, Integer> delta = new HashMap<>();
        Map<AssetColorizer.Fail, Integer> reasons = new EnumMap<>(AssetColorizer.Fail.class);
        Map<AssetColorizer.Fail, List<String>> samples = new EnumMap<>(AssetColorizer.Fail.class);
        int batchSize = Math.max(1, cfg.fallbackBatchSize);
        int sleepMs = Math.max(0, cfg.fallbackSleepMs);
        try {
            log.info("[ChromaAtlas/fallback] 开始：{} 个候选（batch={} sleep={}ms dryRun={}）",
                    candidates.size(), batchSize, sleepMs, dryRun);

            for (String id : candidates) {
                Integer hit = cache.get(id);
                if (hit != null) {
                    reused++;
                    delta.put(id, hit);
                } else {
                    int c = colorizer.colorOf(id);
                    if (c > 0) {
                        computed++;
                        if (colorizer.lastSource() == AssetColorizer.Source.ITEM
                                && itemRescued.size() < SAMPLE_PER_REASON) {
                            itemRescued.add(id);
                        }
                        cache.put(id, c, cache.fingerprintFor(id));
                        delta.put(id, c);
                    } else {
                        failed++;
                        AssetColorizer.Fail why = colorizer.lastFail();
                        reasons.merge(why, 1, Integer::sum);
                        List<String> bucket = samples.get(why);
                        if (bucket == null) {
                            bucket = new ArrayList<>(SAMPLE_PER_REASON);
                            samples.put(why, bucket);
                        }
                        if (bucket.size() < SAMPLE_PER_REASON) {
                            bucket.add(id);
                        }
                    }
                }
                if ((reused + computed + failed) % batchSize == 0) {
                    batches++;
                    if (!dryRun && !delta.isEmpty()) {
                        snap.mergeAuto(delta);
                        AppliedColors.mergeIds(delta);   // 同步到 mixin 实际读的那张表
                        delta.clear();
                    } else {
                        delta.clear();
                    }
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs);
                    }
                }
            }
            if (!dryRun && !delta.isEmpty()) {
                snap.mergeAuto(delta);
                AppliedColors.mergeIds(delta);
                delta.clear();
            }
            cache.save(cacheFile, log);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            lastComputed = computed;
            lastReused = reused;
            lastFailed = failed;
            lastElapsedMs = ms;
            lastPackHits = colorizer.packHits();
            lastJarHits = colorizer.jarHits();
            lastReasons = Collections.unmodifiableMap(new EnumMap<>(reasons));
            lastSamples = freeze(samples);
            lastItemTried = colorizer.itemTried();
            lastItemHit = colorizer.itemHit();
            lastItemRescued = Collections.unmodifiableList(new ArrayList<>(itemRescued));
            log.info("[ChromaAtlas/fallback] 完成：computed={} reused={} failed={} batches={} "
                            + "贴图解码成功/失败={}/{} 来源 pack/jar={}/{} item兜底={}/{} 快照={} 耗时={}ms",
                    computed, reused, failed, batches,
                    colorizer.decodedTextures(), colorizer.failedTextures(),
                    lastPackHits, lastJarHits,
                    lastItemHit, lastItemTried,
                    snap.size(), ms);
            if (!itemRescued.isEmpty()) {
                log.info("[ChromaAtlas/fallback] 物品模型兜底救回 {} 条：{}",
                        lastItemHit, String.join(", ", itemRescued));
            }
            reportFailures(log, failed, reasons, samples);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.info("[ChromaAtlas/fallback] 被中断，已算的 {} 条保留", computed + reused);
        } catch (Throwable t) {
            log.error("[ChromaAtlas/fallback] 线程异常（不影响游戏，已算的结果仍有效）", t);
        } finally {
            colorizer.close();
            running = false;
        }
    }

    /**
     * M-13：把失败集打印成人能决策的形状 —— 一行分类计数 + 每类一行样例 id。
     *
     * <p>★为什么保留 id 而不是只留计数★ {@code NO_MODEL_FILE} 里如果出现 OBJ / 自定义 loader
     * 的方块，M-16（baked-quad）才有价值；只看「12 个」永远判断不了，必须看见名字。
     */
    private static void reportFailures(Logger log, int failed,
                                       Map<AssetColorizer.Fail, Integer> reasons,
                                       Map<AssetColorizer.Fail, List<String>> samples) {
        if (failed <= 0) {
            return;
        }
        log.info("[ChromaAtlas/fallback] 算不出色 {} 个，按原因：{}", failed, summaryOf(reasons));
        for (Map.Entry<AssetColorizer.Fail, Integer> e : sortedByCount(reasons)) {
            List<String> ids = samples.get(e.getKey());
            log.info("[ChromaAtlas/fallback]   算不出色[{}] {} 个：{}",
                    e.getKey(), e.getValue(), ids == null ? "-" : String.join(", ", ids));
        }
    }

    /** {@code A=3 B=1} 形式的一行摘要，按计数从大到小。 */
    private static String summaryOf(Map<AssetColorizer.Fail, Integer> reasons) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<AssetColorizer.Fail, Integer> e : sortedByCount(reasons)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey().name()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** EnumMap 的迭代顺序是枚举声明顺序，日志里要让「最多的原因」排第一。 */
    private static List<Map.Entry<AssetColorizer.Fail, Integer>> sortedByCount(
            Map<AssetColorizer.Fail, Integer> reasons) {
        List<Map.Entry<AssetColorizer.Fail, Integer>> list = new ArrayList<>(reasons.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list;
    }

    private static Map<AssetColorizer.Fail, List<String>> freeze(
            Map<AssetColorizer.Fail, List<String>> samples) {
        Map<AssetColorizer.Fail, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<AssetColorizer.Fail, List<String>> e : samples.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
