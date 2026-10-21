package com.akira.chromaatlas.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M2 运行时探针（只读）。遍历注册表统计每个命名空间的 NONE(mapColor.id==0) 数量，
 * 产出两文件：
 * <pre>
 *   config/mapcolor_probe.csv          namespace,id,mapColorId      （逐方块）
 *   config/mapcolor_probe_summary.csv  namespace,total,none,ratio   （按 ns 汇总）
 * </pre>
 *
 * <p>只读：不写任何方块属性、不注册任何东西。可以安全地装进正式实例跑。
 *
 * <p>为什么要有它：静态扫描（M1）无法区分 {@code Properties.copy(vanilla)} 继承来的颜色，
 * 只有运行时读 {@code getMapColor} 才是真值。
 */
public final class Probe {

    private Probe() {
    }

    private static volatile boolean done;

    /** 幂等：客户端 / 服务端事件可能各触发一次，只跑第一次。 */
    public static void runOnce(String phase, Logger log) {
        if (done) {
            return;
        }
        synchronized (Probe.class) {
            if (done) {
                return;
            }
            done = true;
        }
        try {
            run(phase, log);
        } catch (Throwable t) {
            log.error("[ChromaAtlas/probe] 探针自身失败（不影响游戏）", t);
        }
    }

    private static void run(String phase, Logger log) throws Exception {
        long t0 = System.nanoTime();

        Path cfgDir = Platform.gameDir().resolve("config");
        Files.createDirectories(cfgDir);

        Map<String, int[]> perNs = new TreeMap<>();   // ns -> {total, none}
        List<String> rows = new ArrayList<>();
        int total = 0;
        int none = 0;
        int err = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id;
            try {
                id = BuiltInRegistries.BLOCK.getKey(block);
            } catch (Throwable t) {
                err++;
                continue;
            }
            int colorId;
            try {
                BlockState st = block.defaultBlockState();
                MapColor c = st.getMapColor(null, null);
                colorId = (c == null) ? -1 : c.id;
            } catch (Throwable t) {
                // 第三方 getMapColor 可能用 BlockView/BlockPos，传 null 会炸 -> 记为 -2（未知）
                colorId = -2;
            }
            total++;
            if (colorId == 0) {
                none++;
            }
            int[] agg = perNs.computeIfAbsent(id.getNamespace(), k -> new int[2]);
            agg[0]++;
            if (colorId == 0) {
                agg[1]++;
            }
            rows.add(id.getNamespace() + "," + id.getPath() + "," + colorId);
        }

        Files.write(cfgDir.resolve("mapcolor_probe.csv"), rows, StandardCharsets.UTF_8);

        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(perNs.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[1]).reversed()
                .thenComparing(Map.Entry::getKey));

        List<String> summary = new ArrayList<>();
        summary.add("namespace,total,none,ratio");
        for (Map.Entry<String, int[]> e : sorted) {
            int[] v = e.getValue();
            summary.add(e.getKey() + "," + v[0] + "," + v[1] + ","
                    + String.format(java.util.Locale.ROOT, "%.4f", v[0] == 0 ? 0.0 : (double) v[1] / v[0]));
        }
        Files.write(cfgDir.resolve("mapcolor_probe_summary.csv"), summary, StandardCharsets.UTF_8);

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.info("[ChromaAtlas/probe] phase={} blocks={} none={} unknown={} ns={} 耗时 {}ms -> {}",
                phase, total, none, err, perNs.size(), ms, cfgDir.resolve("mapcolor_probe_summary.csv"));

        // 前 25 个 NONE 大户直接打日志，方便从 latest.log 里一眼看到
        int printed = 0;
        for (Map.Entry<String, int[]> e : sorted) {
            if (printed++ >= 25) {
                break;
            }
            log.info("[ChromaAtlas/probe]   {}/{} NONE={}", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
    }

    /** 供 Preflight 复用的容器写出目录。 */
    public static Path configDir() {
        return Platform.gameDir().resolve("config");
    }
}
