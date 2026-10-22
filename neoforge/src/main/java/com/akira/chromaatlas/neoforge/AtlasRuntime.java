package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.core.AtlasConfig;
import com.akira.chromaatlas.core.ColorSnapshot;
import com.akira.chromaatlas.core.ColorTable;
import com.akira.chromaatlas.core.Resolver;
import com.akira.chromaatlas.core.TransparencyIndex;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.core.registries.BuiltInRegistries;
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
    /** M-8：透明方块清单（玻璃/窗格/天窗），补色时一律跳过。 */
    private static volatile TransparencyIndex transparency = TransparencyIndex.EMPTY;
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
     * M9：装了 Xaero 却用 {@code fill-none}/{@code force} 时打一条显式提醒。
     *
     * <p>依据：Xaero 的 {@code MinimapWriter#calculateBlockColors} 在「没有原版色」时走
     * {@code loadBlockColourFromTexture}（javap 实证）；我们一补色，它就走 {@code getMapColor}，
     * 每块只有一色 ⇒ 木纹/花纹细节全丢。默认已改 {@code fill-missing-model}，
     * 但旧配置文件里的 {@code fill-none} 会一直生效，所以必须提示而不是静默。
     */
    private static boolean xaeroPresent() {
        try {
            net.neoforged.fml.ModList ml = net.neoforged.fml.ModList.get();
            return ml.isLoaded("xaerominimap") || ml.isLoaded("xaeroworldmap");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ★M9 第 18 轮★ {@code auto} 策略解析 + 降级提醒。
     *
     * <p>为什么要有 {@code auto}：出厂表里 {@code m=0} 的条目必然 {@code c=-1}
     * （无模型 ⇒ 无贴图 ⇒ 算不出色），所以 {@code fill-missing-model} 结构上一条都补不了。
     * 把默认值定成它等于默认关掉 mod。改成按环境自动选：
     * 装 Xaero ⇒ 不动（Xaero 自己按贴图着色，补色=降级）；没装 ⇒ fill-none（FTB/原版地图里 NONE 是纯黑）。
     */
    private static void resolveStrategy(AtlasConfig cfg, Logger log) {
        boolean x = xaeroPresent();
        String eff = cfg.applyAuto(x);
        log.info("[ChromaAtlas] 策略解析：colorStrategy={} ⇒ 实际 {}（Xaero {}）",
                cfg.colorStrategy, eff, x ? "已装" : "未装");
        if (x && ("fill-none".equals(eff) || "force".equals(eff))) {
            log.info("[ChromaAtlas] ★策略提醒★ 实际策略 {} 会把 Xaero 原本按贴图采样的方块改写成单色"
                    + "（纹理细节丢失）；要无降级请改 auto 或 fill-missing-model", eff);
        }
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
        transparency = AtlasFiles.loadTransparency(dir, log);
        SNAPSHOT.replaceAuto(table.autoView());

        // ★顺序★ 必须先解析 auto 再打印，否则两行日志自相矛盾
        // （第 20 轮实测：先打 `strategy=fill-missing-model`，下一行又说「实际 fill-none」）
        resolveStrategy(config, log);
        log.info("[ChromaAtlas] 策略：strategy={} onlyFillNone={} dryRun={} runtimeFallback={} verbose={} 黑白名单={}/{}",
                config.strategy(), config.onlyFillNone, config.dryRun, config.runtimeFallback,
                config.verbose, config.enabledNamespaces.size(), config.disabledNamespaces.size());

        Plan plan = scanAndPlan(table, config, log);
        int written = installPlan(plan, config, log);
        AppliedColors.appliedManual = plan.manual;
        AppliedColors.appliedAuto = plan.auto;
        AppliedColors.skippedHasColor = plan.skipHasColor;
        AppliedColors.skippedNamespace = plan.skipNs;
        AppliedColors.skippedNoData = plan.skipNoData;
        AppliedColors.skippedInvalid = plan.skipInvalid;

        log.info("[ChromaAtlas] 应用统计：写入 {}（手工 {} / 自动 {}）/ 跳过 已有色 {} 命名空间 {} 无数据 {} 非法 {} "
                        + "/ 透明 {} / 注册表 {} 方块 / 基线 NONE {} / {}ms",
                written, plan.manual, plan.auto,
                plan.skipHasColor, plan.skipNs, plan.skipNoData, plan.skipInvalid, plan.skipTransparent,
                plan.total, plan.none, (System.nanoTime() - t0) / 1_000_000L);

        verify(plan, log, config.mixinGetMapColor && !config.dryRun);

        if (!config.mixinGetMapColor) {
            // 装不进去 ⇒ 兜底算了也没地方放，直接不启动（否则白烧 CPU）
            log.info("[ChromaAtlas] mixinGetMapColor=false ⇒ 兜底线程不启动（算出来也无处生效），候选 {} 条不动",
                    plan.candidates.size());
        } else if (config.runtimeFallback && !plan.candidates.isEmpty()) {
            Path configRoot = AtlasFiles.dir(dir).getParent();
            Path cacheFile = configRoot.resolve(config.runtimeCacheFile);
            RuntimeFallback.start(plan.candidates, config, SNAPSHOT, cacheFile, log, config.dryRun);
        } else if (!config.runtimeFallback) {
            log.info("[ChromaAtlas] runtimeFallback=false ⇒ 只用静态表，未命中保持原版 NONE");
        }
        lastStrategy = config.strategy();
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
        transparency = AtlasFiles.loadTransparency(gameDir, log);
        SNAPSHOT.replaceAuto(table.autoView());
        // ★R83 第 27 轮★ reload 必须和 boot 走同一条解析链：config 是新 new 的，
        // effectiveStrategy 为 null ⇒ strategy() 会退回硬编码的 fill-missing-model
        // （默认 colorStrategy=auto 的机器上，reload 一次就把已补的色全抹掉，日志还看不出来）。
        resolveStrategy(config, log);
        log.info("[ChromaAtlas] 策略：strategy={} onlyFillNone={} dryRun={} runtimeFallback={} verbose={} 黑白名单={}/{}",
                config.strategy(), config.onlyFillNone, config.dryRun, config.runtimeFallback,
                config.verbose, config.enabledNamespaces.size(), config.disabledNamespaces.size());
        Plan plan = scanAndPlan(table, config, log);
        int written = installPlan(plan, config, log);
        verify(plan, log, config.mixinGetMapColor && !config.dryRun);
        log.info("[ChromaAtlas] reload 完成：strategy={} 写入 {} / 候选 {} / {}ms",
                config.strategy(), written,
                plan.candidates.size(), (System.nanoTime() - t0) / 1_000_000L);
        lastStrategy = config.strategy();
    }

    /**
     * 按配置把计划装进 {@link AppliedColors}，返回真正写入的条数。
     *
     * <p>★R84 第 27 轮★ {@code mixinGetMapColor=false} 以前是**零读取方的配置键**
     * （只在 loadConfig / writeDefaultConfig / SelfTest 出现）⇒ 玩家关掉它毫无反应。
     * 现在落地为「不安装覆盖色」：两条 mixin 都是「命中表就返回，否则放行原版」，
     * 表空 = 完全放行 ⇒ 语义等价于关掉 overlay（mixin 本身在类加载期就定了，运行期只能这样关）。
     */
    private static int installPlan(Plan plan, AtlasConfig cfg, Logger log) {
        Map<Block, Integer> apply = plan.planned;
        if (!cfg.mixinGetMapColor) {
            log.info("[ChromaAtlas] ★配置★ mixinGetMapColor=false ⇒ 不安装覆盖色（计划 {} 条全部不生效，"
                    + "两张 mixin 都放行原版）", plan.planned.size());
            apply = java.util.Collections.emptyMap();
        }
        AppliedColors.install(apply, BY_ID, cfg.dryRun, log);
        return cfg.dryRun ? 0 : apply.size();
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
        int skipTransparent;
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
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id;
            int cur;
            try {
                id = BuiltInRegistries.BLOCK.getKey(block);
                BlockState st = block.defaultBlockState();
                MapColor c = st.getMapColor(null, null);
                cur = c == null ? 0 : c.id;
            } catch (Throwable t) {
                continue;
            }
            p.total++;
            Integer known = BOOT_COLOR.get(block);
            if (known != null) {
                cur = known;      // reload：用第一次扫描的基线，防止自我污染
            } else {
                base.put(block, cur);
            }
            byId.put(id.toString(), block);
            if (cur == 0) {
                p.none++;
                p.byNamespaceNone.merge(id.getNamespace(), 1, Integer::sum);
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
                d = Resolver.resolve(id, cur, tbl, cfg, transparency);
            } catch (Throwable t) {
                continue;
            }
            switch (d.reason) {
                case OVERRIDE:
                    p.manual++;
                    break;
                case AUTO:
                    p.auto++;
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
                case REFUSED_TRANSPARENT:
                    p.skipTransparent++;
                    break;
                default:
                    break;
            }
            if (d.shouldApply()) {
                p.planned.put(block, d.result);
            } else if (d.reason == Resolver.Reason.SKIP_NO_DATA && cur == 0
                    && cfg.namespaceAllowed(ColorTable.ns(id))) {
                p.skipNoData++;
                p.candidates.add(id);
            } else if (d.reason == Resolver.Reason.SKIP_NO_DATA) {
                p.skipNoData++;
            }
        }

        // ★M8：配置校验必须显式报错，不能静默★
        // 手工表 = 人写的，任何错都报；自动表是离线产物，本实例没装的模组条目属正常，只报数量。
        List<String> badManual = tbl.invalidManualIds();
        if (!badManual.isEmpty()) {
            log.info("[ChromaAtlas] ★配置错误★ overrides.json 非法色号 {} 条（合法 1..{}）：{}",
                    badManual.size(), com.akira.chromaatlas.core.VanillaMapColors.MAX_ID, head(badManual, 10));
        }
        List<String> unkManual = tbl.unknownManualIds(byId.keySet());
        if (!unkManual.isEmpty()) {
            log.info("[ChromaAtlas] ★配置错误★ overrides.json 有 {} 个 id 不在当前注册表里（拼错 / 模组已卸载）：{}",
                    unkManual.size(), head(unkManual, 10));
        }
        int unkAuto = tbl.unknownAutoCount(byId.keySet());
        if (unkAuto > 0) {
            log.info("[ChromaAtlas] mapcolors.json 有 {} 条属于本实例没装的方块（出厂表覆盖更多模组，正常）", unkAuto);
        }

        List<Map.Entry<String, Integer>> top = new ArrayList<>(p.byNamespaceNone.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(top.get(i).getKey()).append('=').append(top.get(i).getValue());
        }
        log.info("[ChromaAtlas] NONE 基线：{} 个（{} 个 ns 有缺口）Top10 {}", p.none, p.byNamespaceNone.size(), sb);
        return p;
    }

    /** 日志里只列前 n 条，避免把 3 万条表的差异刷满屏幕。 */
    private static String head(List<String> list, int n) {
        if (list.size() <= n) {
            return list.toString();
        }
        return list.subList(0, n) + " …(共 " + list.size() + " 条)";
    }

    /**
     * 自证：装表后抽样回读 {@code getMapColor}，必须等于计划值。
     *
     * <p>这条是 M4 的「mixin 真的挂上了」的无头证据 —— 不需要进世界，也不用截图。
     */
    private static void verify(Plan plan, Logger log, boolean installed) {
        if (!installed) {
            AppliedColors.verifySampled = 0;
            AppliedColors.verifyOk = 0;
            AppliedColors.verifyMismatch = 0;
            log.info("[ChromaAtlas] verify 跳过（{}）",
                    config.dryRun ? "dryRun=true，未写入" : "mixinGetMapColor=false，未安装覆盖色");
            return;
        }
        int sampled = 0, ok = 0, bad = 0;
        for (Map.Entry<Block, Integer> e : plan.planned.entrySet()) {
            if (sampled >= 200) {
                break;
            }
            sampled++;
            try {
                MapColor c = e.getKey().defaultBlockState().getMapColor(null, null);
                if (c != null && c.id == e.getValue()) {
                    ok++;
                } else {
                    bad++;
                    if (bad <= 5) {
                        log.info("[ChromaAtlas] verify 不一致：{} 期望 {} 实际 {}",
                                BuiltInRegistries.BLOCK.getKey(e.getKey()),
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
        log.info("[ChromaAtlas] verify：sampled={} ok={} mismatch={}（mixin getMapColor 回读）",
                sampled, ok, bad);
    }
}
