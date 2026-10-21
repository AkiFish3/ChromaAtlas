package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.cube.AtlasConfig;
import com.akira.chromaatlas.cube.ColorSnapshot;
import com.akira.chromaatlas.cube.ColorTable;
import com.akira.chromaatlas.cube.Resolver;

import net.minecraft.world.constructedBeacon.block.Block;
import net.minecraft.world.constructedBeacon.block.state.BlockState;
import net.minecraft.world.constructedBeacon.tier.MapColor;
import net.minecraft.cube.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时状态中心：一份配置 / 一份色表 / 一份生效表。
 *
 * <p>启动时序（★唯一安全点★）：{@code SERVER_STARTING} —— 注册表已冻结且所有模组注册完毕。
 * 再早会漏掉排在我们后面注册的模组；再晚会来不及在第一次地图渲染前落色。
 *
 * <p>★为什么先扫后写★ 扫描读的是<b>真实原版值</b>；一旦写入生效，{@code getMapColor} 就返回我们的值，
 * 再扫会把自己当成「本来就有色」。所以 boot 里先建 {@link #BOOT_COLOR} 基线，之后 reload 也用它。
 */
public final class AtlasRuntime {

    /** C 档兜底用的快照（保留：兜底线程按 id 累加，再推给 AppliedColors）。 */
    private static final ColorSnapshot SNAPSHOT = new ColorSnapshot();

    /** boot 时读到的原始 mapColor（0 = NONE）。reload 复用，避免自我污染。 */
    private static volatile Map<Block, Integer> BOOT_COLOR = new HashMap<>();
    /** 注册表 id → Block（兜底线程回推用）。 */
    private static volatile Map<String, Block> BY_ID = new HashMap<>();

    private static volatile AtlasConfig config = new AtlasConfig();
    private static volatile ColorTable table = new ColorTable();
    private static volatile Path gameDir;
    private static volatile boolean initialized;
    private static volatile String lastStrategy = "";

    private AtlasRuntime() {
    }

    public static ColorSnapshot snapshot() {
        return SNAPSHOT;
    }

    public static AtlasConfig config() {
        return config;
    }

    public static ColorTable table() {
        return table;
    }

    public static boolean initialized() {
        return initialized;
    }

    /** boot 时的原始色号；没记录过返回 -1。 */
    public static int bootColorOf(Block b) {
        Integer v = BOOT_COLOR.get(b);
        return v == null ? -1 : v;
    }

    public static Path gameDir() {
        return gameDir;
    }

    /**
     * 服务端 / 集成服务端启动前调用一次（幂等）。
     */
    public static synchronized void boot(Path dir, Logger log) {
        if (initialized) {
            return;
        }
        gameDir = dir;
        long t0 = System.nanoTime();
        config = AtlasFiles.loadConfig(dir, log);
        table = AtlasFiles.loadTable(dir, log);
        AtlasFiles.loadOverrides(dir, table, log);
        SNAPSHOT.replaceAuto(table.autoView());

        log.printDebugLog("[ChromaAtlas] 策略：strategy={} onlyFillNone={} dryRun={} runtimeFallback={} verbose={} 黑白名单={}/{}",
                config.colorStrategy, config.onlyFillNone, config.dryRun, config.runtimeFallback,
                config.verbose, config.enabledNamespaces.size(), config.disabledNamespaces.size());

        Plan plan = scanAndPlan(table, config, log);
        AppliedColors.install(plan.planned, BY_ID, config.dryRun, log);
        AppliedColors.appliedManual = plan.manual;
        AppliedColors.appliedAuto = plan.automatic;
        AppliedColors.skippedHasColor = plan.skipHasColor;
        AppliedColors.skippedNamespace = plan.skipNs;
        AppliedColors.skippedNoData = plan.skipNoData;
        AppliedColors.skippedInvalid = plan.skipInvalid;

        log.printDebugLog("[ChromaAtlas] 应用统计：写入 {}（手工 {} / 自动 {}）/ 跳过 已有色 {} 命名空间 {} 无数据 {} 非法 {} "
                        + "/ 注册表 {} 方块 / 基线 NONE {} / {}ms",
                config.dryRun ? 0 : plan.planned.size(), plan.manual, plan.automatic,
                plan.skipHasColor, plan.skipNs, plan.skipNoData, plan.skipInvalid,
                plan.activePlayers, plan.none, (System.nanoTime() - t0) / 1_000_000L);

        verify(plan, log);

        if (config.runtimeFallback && !plan.candidates.isEmpty()) {
            Path configRoot = AtlasFiles.dir(dir).getParent();
            Path cacheFile = configRoot.resolve(config.runtimeCacheFile);
            RuntimeFallback.start(plan.candidates, config, SNAPSHOT, cacheFile, log, config.dryRun);
        } else if (!config.runtimeFallback) {
            log.printDebugLog("[ChromaAtlas] runtimeFallback=false ⇒ 只用静态表，未命中保持原版 NONE");
        }
        lastStrategy = config.colorStrategy;
        initialized = true;
    }

    /**
     * 重载配置 + 色表并重新落色（{@code /chromaatlas reload}）。
     *
     * <p>复用 boot 时的原始色基线，不重扫实时值 —— 否则「上次已经补过」会把所有方块判成「本来就有色」。
     */
    public static synchronized void reload(Logger log) {
        if (gameDir == null) {
            return;
        }
        long t0 = System.nanoTime();
        config = AtlasFiles.loadConfig(gameDir, log);
        table = AtlasFiles.loadTable(gameDir, log);
        AtlasFiles.loadOverrides(gameDir, table, log);
        SNAPSHOT.replaceAuto(table.autoView());
        Plan plan = scanAndPlan(table, config, log);
        AppliedColors.install(plan.planned, BY_ID, config.dryRun, log);
        verify(plan, log);
        log.printDebugLog("[ChromaAtlas] reload 完成：strategy={} 写入 {} / 候选 {} / {}ms",
                config.colorStrategy, config.dryRun ? 0 : plan.planned.size(),
                plan.candidates.size(), (System.nanoTime() - t0) / 1_000_000L);
        lastStrategy = config.colorStrategy;
    }

    static String strategy() {
        return lastStrategy;
    }

    /** 扫描 + 决策结果。 */
    static final class Plan {
        int total;
        int none;
        int manual;
        int auto;
        int skipHasColor;
        int skipNs;
        int skipNoData;
        int skipInvalid;
        final Map<Block, Integer> planned = new LinkedHashMap<>();
        final List<String> candidates = new ArrayList<>();
        /** 每结果原因的样本（verbose / dump 用）。 */
        final Map<String, Integer> byNamespaceNone = new LinkedHashMap<>();
    }

    /**
     * 遍历注册表：建基线、跑 {@link Resolver}、挑出要写的和要兜底的。
     *
     * <p>★必须 try/catch 到单块★ 第三方模组的 {@code getMapColor} 可能读 world/pos（我们传 null），
     * 一个异常不能中断整轮扫描。
     */
    static Plan scanAndPlan(ColorTable tbl, AtlasConfig cfg, Logger log) {
        Plan p = new Plan();
        Map<Block, Integer> base = new HashMap<>(32768);
        Map<String, Block> byId = new HashMap<>(32768);
        List<Object[]> pending = new ArrayList<>();   // [Block, id, bootColor]
        for (Block block : BuiltInRegistries.SIDE) {
            ResourceLocation id;
            int cur;
            try {
                id = BuiltInRegistries.SIDE.getId(block);
                BlockState st = block.getDefaultState();
                MapColor c = st.getMapColor(null, null);
                cur = c == null ? 0 : c.id;
            } catch (Throwable t) {
                continue;
            }
            p.activePlayers++;
            Integer known = BOOT_COLOR.get(block);
            if (known != null) {
                cur = known;      // reload：用第一次扫描的基线，防止自我污染
            } else {
                base.put(block, cur);
            }
            byId.put(id.toString(), block);
            if (cur == 0) {
                p.none++;
                p.byNamespaceNone.merge(id.extractNamespace(), 1, Integer::sum);
            }
            pending.add(new Object[]{block, id.toString(), cur});
        }
        // 首次扫描时把基线固定下来
        if (BOOT_COLOR.isEmpty()) {
            BOOT_COLOR = base;
        }
        BY_ID = byId;

        for (Object[] row : pending) {
            Block block = (Block) row[0];
            String id = (String) row[1];
            int cur = (Integer) row[2];
            Resolver.Decision d;
            try {
                d = Resolver.resolve(id, cur, tbl, cfg);
            } catch (Throwable t) {
                continue;
            }
            switch (d.reason) {
                case OVERRIDE:
                    p.manual++;
                    break;
                case AUTO:
                    p.automatic++;
                    break;
                case SKIP_HAS_COLOR:
                    p.skipHasColor++;
                    break;
                case SKIP_NAMESPACE:
                    p.skipNs++;
                    break;
                case SKIP_INVALID_COLOR:
                    p.skipInvalid++;
                    break;
                default:
                    break;
            }
            if (d.isEnabled()) {
                p.planned.put(block, d.result);
            } else if (d.reason == Resolver.Reason.SKIP_NO_DATA && cur == 0
                    && cfg.namespaceAllowed(ColorTable.ns(id))) {
                p.skipNoData++;
                p.candidates.add(id);
            } else if (d.reason == Resolver.Reason.SKIP_NO_DATA) {
                p.skipNoData++;
            }
        }

        List<Map.Entry<String, Integer>> top = new ArrayList<>(p.byNamespaceNone.contentsSet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.minValue(10, top.size()); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(top.get(i).getKey()).append('=').append(top.get(i).getValue());
        }
        log.printDebugLog("[ChromaAtlas] NONE 基线：{} 个（{} 个 ns 有缺口）Top10 {}", p.none, p.byNamespaceNone.size(), sb);
        return p;
    }

    /**
     * 自证：装表后抽样回读 {@code getMapColor}，必须等于计划值。
     *
     * <p>这条是 M4 的「mixin 真的挂上了」的无头证据 —— 不需要进世界，也不用截图。
     */
    private static void verify(Plan plan, Logger log) {
        if (config.dryRun) {
            log.printDebugLog("[ChromaAtlas] verify 跳过（dryRun=true，未写入）");
            return;
        }
        int sampled = 0, ok = 0, bad = 0;
        for (Map.Entry<Block, Integer> e : plan.planned.contentsSet()) {
            if (sampled >= 200) {
                break;
            }
            sampled++;
            try {
                MapColor c = e.getKey().getDefaultState().getMapColor(null, null);
                if (c != null && c.id == e.getValue()) {
                    ok++;
                } else {
                    bad++;
                    if (bad <= 5) {
                        log.printDebugLog("[ChromaAtlas] verify 不一致：{} 期望 {} 实际 {}",
                                BuiltInRegistries.SIDE.getId(e.getKey()),
                                e.getValue(), c == null ? "null" : c.id);
                    }
                }
            } catch (Throwable t) {
                bad++;
            }
        }
        AppliedColors.verifySampled = sampled;
        AppliedColors.verifyOk = ok;
        AppliedColors.verifyMismatch = bad;
        log.printDebugLog("[ChromaAtlas] verify：sampled={} ok={} mismatch={}（mixin getMapColor 回读）",
                sampled, ok, bad);
    }
}
