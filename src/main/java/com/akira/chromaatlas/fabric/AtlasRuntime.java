package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.AtlasConfig;
import com.akira.chromaatlas.core.ColorSnapshot;
import com.akira.chromaatlas.core.ColorTable;
import com.akira.chromaatlas.core.Resolver;
import com.akira.chromaatlas.core.TransparencyIndex;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
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
    /** ★M-10 第 31 轮★ 上次扫描成功计入的方块数（0 = 扫描空转，boot 允许重试）。 */
    private static volatile int lastTotal = -1;
    /** ★M-10★ 上次扫描的循环体进入次数 / 单块异常次数（诊断用）。 */
    private static volatile int lastIterated;
    private static volatile int lastCaught;
    /**
     * ★第 32 轮★ per-state {@code mapColor} 字段的懒查与反射读取统一在 {@link RawColors} 里，
     * 本类不再自己持有（原先只有这里一处实现，Probe / Preflight 各自又写了一遍 null world）。
     */
    /** ★M-10★ 基线取色走的路径计数（让日志能自证用的是哪一级，不靠猜）。 */
    private static volatile int rawViaReflect;
    private static volatile int rawViaBlock;

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

    /** ★M-10★ 上次扫描成功计入的方块数（-1 = 还没跑过；0 = 空转，可重试）。 */
    public static int lastScannedBlocks() {
        return lastTotal;
    }

    /** ★M-10★ 上次扫描进入循环体的次数（= 注册表迭代长度，异常也计入）。 */
    public static int lastIterated() {
        return lastIterated;
    }

    /** ★M-10★ 上次扫描单块抛异常的次数。 */
    public static int lastCaught() {
        return lastCaught;
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
     * ★M-7 第 34 轮★ {@code auto} 策略解析。
     *
     * <p>旧版按「装没装 Xaero」二选一，装了就走 {@code fill-missing-model} —— 而后者是
     * 结构性空操作（{@code m=0 ⇒ c=-1}），等于在<b>最主流的机器上默认把 mod 关掉</b>。
     * 第 33 轮双地图实拍（同存档 / 同坐标 / 同帧）证明补色对 Xaero 是纯收益：
     * coc 方块未补色时 Xaero 直接画黑块（整图 14.969% 纯黑），补色后降到 0.151%。
     * 故 {@code auto} 一律 = {@code fill-none}，只作旧配置的兼容别名。
     */
    private static void resolveStrategy(AtlasConfig cfg, Logger log) {
        String eff = cfg.applyAuto();
        log.info("[ChromaAtlas] 策略解析：colorStrategy={} ⇒ 实际 {}", cfg.colorStrategy, eff);
        if ("fill-missing-model".equals(eff)) {
            log.info("[ChromaAtlas] ★策略提醒★ fill-missing-model 是结构性空操作"
                    + "（m=0 必然 c=-1 ⇒ 一条都补不了，实际写入 0 条）；想让地图有颜色请用 fill-none");
        }
    }

    /**
     * 服务端 / 集成服务端启动前调用一次（幂等）。
     *
     * <p>★M-10 第 31 轮★ 幂等条件从「跑过就锁死」收紧为「跑过<b>且扫到方块</b>」：
     * 1.21.1 NeoForge 客户端在 {@code TagsUpdatedEvent} 上扫到 0 块（第 30 轮实机），
     * 旧的 `if (initialized) return` 会让后续修正后的触发点再也进不来。
     */
    public static synchronized void boot(Path dir, Logger log) {
        if (initialized && lastTotal > 0) {
            return;
        }
        gameDir = dir;
        long t0 = System.nanoTime();
        rawViaReflect = 0;
        rawViaBlock = 0;
        config = AtlasFiles.loadConfig(dir, log);
        table = AtlasFiles.loadTable(dir, log);
        AtlasFiles.loadOverrides(dir, table, log);
        // ★M-17★ 学习结果必须在手工覆盖之后装：两者都只补空位，但手工永远最高。
        AtlasFiles.loadLearned(dir, table, log);
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
        applyStats(plan);
        log.info("[ChromaAtlas] 应用统计：写入 {}（手工 {} / 自动 {} / 学习 {}）/ 跳过 已有色 {} 命名空间 {} 无数据 {} 非法 {} "
                        + "/ 透明 {} / 注册表 {} 方块（遍历 {} / 抛异常 {} / 首例 {}）/ 基线 NONE {} / {}ms",
                written, plan.manual, plan.auto, plan.learned,
                plan.skipHasColor, plan.skipNs, plan.skipNoData, plan.skipInvalid, plan.skipTransparent,
                plan.total, plan.iterated, plan.caught,
                plan.firstError == null ? "无" : plan.firstError,
                plan.none, (System.nanoTime() - t0) / 1_000_000L);

        log.info("[ChromaAtlas] 基线取色路径：per-state 反射 {} 块 / 方块级兜底 {} 块（0 = 没走）",
                rawViaReflect, rawViaBlock);
        if (plan.firstTrace != null && !plan.firstTrace.isEmpty()) {
            log.info("[ChromaAtlas] ★M-10 首例栈★ {}", plan.firstTrace);
        }

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
        lastTotal = plan.total;
        lastIterated = plan.iterated;
        lastCaught = plan.caught;
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
        // ★M-17★ reload 必须和 boot 走同一条装载链，否则「导出后 reload 生效」这条验收路径断掉。
        AtlasFiles.loadLearned(gameDir, table, log);
        transparency = AtlasFiles.loadTransparency(gameDir, log);
        SNAPSHOT.replaceAuto(table.autoView());
        // ★R83 第 27 轮★ reload 必须和 boot 走同一条解析链：config 是新 new 的，
        // effectiveStrategy 为 null ⇒ strategy() 会退回硬编码的 fill-none
        // （默认 colorStrategy=auto 的机器上，reload 一次就把已补的色全抹掉，日志还看不出来）。
        resolveStrategy(config, log);
        log.info("[ChromaAtlas] 策略：strategy={} onlyFillNone={} dryRun={} runtimeFallback={} verbose={} 黑白名单={}/{}",
                config.strategy(), config.onlyFillNone, config.dryRun, config.runtimeFallback,
                config.verbose, config.enabledNamespaces.size(), config.disabledNamespaces.size());
        Plan plan = scanAndPlan(table, config, log);
        int written = installPlan(plan, config, log);
        applyStats(plan);
        verify(plan, log, config.mixinGetMapColor && !config.dryRun);
        log.info("[ChromaAtlas] reload 完成：strategy={} 写入 {} / 候选 {} / {}ms",
                config.strategy(), written,
                plan.candidates.size(), (System.nanoTime() - t0) / 1_000_000L);
        lastStrategy = config.strategy();
        lastTotal = plan.total;
    }

    /**
     * 把本轮 plan 的计数抄进 {@link AppliedColors}（{@code /chromaatlas status} 读的就是这几个字段）。
     *
     * <p>★M-17 修★ 以前这段只写在 {@code boot()} 里，{@code reload()} 之后 status 仍在报上一次的
     * 「手工/自动/学习」明细 —— 总数（{@code AppliedColors.size()}）变了、分项没变，两边对不上。
     * reload 是本 mod 最常用的运维动作（改配置后立刻看效果），统计必须同步刷新。
     */
    private static void applyStats(Plan plan) {
        AppliedColors.appliedManual = plan.manual;
        AppliedColors.appliedAuto = plan.auto;
        AppliedColors.appliedLearned = plan.learned;
        AppliedColors.skippedHasColor = plan.skipHasColor;
        AppliedColors.skippedNamespace = plan.skipNs;
        AppliedColors.skippedNoData = plan.skipNoData;
        AppliedColors.skippedInvalid = plan.skipInvalid;
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
        /** ★M-10★ 进入循环体的次数（含随后抛异常的）—— 与 total 不等就说明 catch 吞了东西。 */
        int iterated;
        /** ★M-10★ 单块 try 抛异常的次数。旧代码写在 catch 之后 ⇒ 全抛就显得「注册表 0」。 */
        int caught;
        /** ★M-10★ 第一个异常（id ⇒ 异常类: 消息），只为定因而留，不做重试逻辑。 */
        String firstError;
        /** ★M-10★ 首例异常的栈（8 帧）。 */
        String firstTrace;
        int none;
        int manual;
        int auto;
        /** ★M-17★ 命中学习结果（learned_overrides.json）的条数。 */
        int learned;
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
     * 读某个 state 的<b>原始</b>地图色号（0 = NONE）。
     *
     * <p>★M-10 第 31 轮定因★ 不能再写 {@code st.getMapColor(null, null)}：1.21.1 客户端上
     * {@code BlockBehaviour$BlockStateBase.getMapColor} 被 <b>bountifulfares 的 mixin</b> 注入
     * （{@code handler$dne001$bountifulfares$getMapColor}），它直接 {@code world.getBlockState(pos)}
     * ⇒ world 传 null 必 NPE。实机证据：遍历 21413 / 抛异常 21413 / 首例 minecraft:air。
     * 这不是「注册表空」，而是我们自己的取色方式在第三方 mixin 面前不成立。
     *
     * <p>所以改成两级：① 反射读 state 里那个 per-state 的 {@code MapColor} 字段
     * （1.20.1 的 {@code getMapColor} 本身就是返回它，语义等价、且不经过任何 mixin）；
     * ② 反射不可用时退回方块级 {@code getDefaultMapColor()}（同样不吃 world）。
     */
    private static int rawColor(BlockState st, Block block) {
        // ★第 32 轮★ 实现搬到 {@link RawColors}：Probe / Preflight 原先还写着
        // getMapColor(null, null)，触发条件一样会被第三方 mixin 打穿（R107 同族）。
        int id = RawColors.reflect(st);
        if (id >= 0) {
            rawViaReflect++;
            return id;
        }
        rawViaBlock++;
        return RawColors.blockLevel(block);
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
        for (Block block : Registries.BLOCK) {
            p.iterated++;      // ★M-10★ 先计数再进 try：抛异常也必须留下痕迹
            Identifier id;
            int cur;
            try {
                id = Registries.BLOCK.getId(block);
                BlockState st = block.getDefaultState();
                cur = rawColor(st, block);     // ★M-10★ 不传 null world（会被第三方 mixin 打穿）
            } catch (Throwable t) {
                p.caught++;
                if (p.firstError == null) {
                    String bid;
                    try {
                        bid = Registries.BLOCK.getId(block).toString();
                    } catch (Throwable ignored) {
                        bid = String.valueOf(block);
                    }
                    p.firstError = bid + " ⇒ " + t.getClass().getName() + ": " + t.getMessage();
                    // ★M-10★ 只留首例的栈（8 帧）：静默 catch 的老病理 —— 没有栈就只能猜根因
                    StackTraceElement[] frames = t.getStackTrace();
                    StringBuilder fr = new StringBuilder();
                    for (int i = 0; i < Math.min(8, frames.length); i++) {
                        if (i > 0) {
                            fr.append(" ← ");
                        }
                        fr.append(frames[i]);
                    }
                    p.firstTrace = fr.toString();
                }
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
                case LEARNED:
                    p.learned++;
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
        int unkLearned = tbl.unknownLearnedCount(byId.keySet());
        if (unkLearned > 0) {
            log.info("[ChromaAtlas] learned_overrides.json 有 {} 条属于本实例没装的方块（跨实例导出，正常）", unkLearned);
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
        int viaState = 0, viaBlock = 0, viaTable = 0;
        for (Map.Entry<Block, Integer> e : plan.planned.entrySet()) {
            if (sampled >= 200) {
                break;
            }
            sampled++;
            Block b = e.getKey();
            BlockState st = b.getDefaultState();
            int actual;
            String via;
            try {
                // ① 状态级：这一条才真正证明「状态级 mixin 挂上了」
                // ★故意保留 null world★ 这里要的正是「走 mixin 后的返回值」；RawColors 会绕开 mixin，
                // 拿来验证等于自己证明自己。抛异常时下面三级降级会如实标注 via=block/table。
                MapColor c = st.getMapColor(null, null);
                actual = c == null ? -1 : c.id;
                via = "state";
            } catch (Throwable t) {
                // ★M-10★ 1.21.1 有 mod（bountifulfares）在状态级 mixin 里直接用 world ⇒ null 必 NPE。
                // 退到方块级（第二条 mixin）与直读表，并如实标注降级层级，不冒充同一档证据。
                try {
                    MapColor c = b.getDefaultMapColor();
                    actual = c == null ? -1 : c.id;
                    via = "block";
                } catch (Throwable t2) {
                    MapColor c = AppliedColors.of(st);
                    actual = c == null ? -1 : c.id;
                    via = "table";
                }
            }
            if (actual == e.getValue()) {
                ok++;
                if ("state".equals(via)) {
                    viaState++;
                } else if ("block".equals(via)) {
                    viaBlock++;
                } else {
                    viaTable++;
                }
            } else {
                bad++;
                if (bad <= 5) {
                    log.info("[ChromaAtlas] verify 不一致：{} 期望 {} 实际 {}（经 {}）",
                            Registries.BLOCK.getId(b), e.getValue(), actual, via);
                }
            }
        }
        AppliedColors.verifySampled = sampled;
        AppliedColors.verifyOk = ok;
        AppliedColors.verifyMismatch = bad;
        log.info("[ChromaAtlas] verify：sampled={} ok={} mismatch={}（回读层级 状态级 {} / 方块级 {} / 直读表 {}）",
                sampled, ok, bad, viaState, viaBlock, viaTable);
    }
}
