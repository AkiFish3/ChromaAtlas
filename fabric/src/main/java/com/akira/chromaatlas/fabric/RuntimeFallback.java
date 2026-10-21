package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.AtlasConfig;
import com.akira.chromaatlas.core.ColorSnapshot;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
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
 */
public final class RuntimeFallback {

    private static volatile boolean running;
    private static volatile int lastComputed = -1;
    private static volatile int lastReused = -1;
    private static volatile int lastFailed = -1;
    private static volatile long lastElapsedMs = -1;

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
        return "computed=" + lastComputed + " reused=" + lastReused + " failed=" + lastFailed
                + " elapsed=" + lastElapsedMs + "ms";
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
        AssetColorizer colorizer = new AssetColorizer(log);
        RuntimeCache cache = RuntimeCache.load(cacheFile, log);
        Map<String, Integer> delta = new HashMap<>();
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
                        cache.put(id, c, cache.fingerprintFor(id));
                        delta.put(id, c);
                    } else {
                        failed++;
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
            log.info("[ChromaAtlas/fallback] 完成：computed={} reused={} failed={} batches={} "
                            + "贴图解码成功/失败={}/{} 快照={} 耗时={}ms",
                    computed, reused, failed, batches,
                    colorizer.decodedTextures(), colorizer.failedTextures(),
                    snap.size(), ms);
            if (failed > 0) {
                log.info("[ChromaAtlas/fallback] 有 {} 个方块算不出色（无模型/贴图缺失/全透明）⇒ 已跳过，未写入 0", failed);
            }
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
}
