package com.akira.chromaatlas.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 运行策略（对应 {@code config/chromaatlas.json}）。
 *
 * <p>默认值即「最保守」：只补 NONE、不 dryRun、不限定命名空间、无黑名单。
 */
public final class AtlasConfig {

    /** 只补「当前 mapColor 为 NONE(0)」的方块；关掉就变成「全部重刷」。 */
    public boolean onlyFillNone = true;

    /** 只记日志不写入。 */
    public boolean dryRun = false;

    /**
     * 补色策略（★M-7 第 34 轮重定档★）：
     * <ul>
     *   <li>{@code fill-none}：<b>默认</b>。补所有 {@code mapColor == NONE} 的方块（透明件除外，见 skipTransparent）</li>
     *   <li>{@code fill-missing-model}：结构性空操作（出厂表 {@code m=0 ⇒ c=-1}），仅为兼容旧配置保留</li>
     *   <li>{@code force}：连本来有色的也覆盖（调试）</li>
     *   <li>{@code auto}：<b>已废弃</b>，等价 {@code fill-none}（旧配置里写着它的机器会自动被修好）</li>
     * </ul>
     *
     * <p>★为什么推翻 M9 的「Xaero 降级」结论★ M9 只看了 {@code loadBlockColourFromTexture}
     * 这条贴图采样兜底路径，推断「补色会抹平纹理」。第 33 轮双地图实拍（同存档 / 同坐标 / 同帧）证伪：
     * Xaero 对 coc 这类自定义模型采样失败 ⇒ 直接画黑块，补色后黑块 14.969% → 0.151%。
     * 所有地图 mod 的着色粒度都是「每方块一色」，<b>不补的结果是黑洞，补了才是平色</b>；
     * 「平色 vs 黑洞」不是降级。两个 mod 对同一批方块的表现一致（FTB 只是地形细节更亮）。
     */
    public String colorStrategy = "fill-none";

    /**
     * {@code auto} 解析后的实际策略；{@code null} = 还没解析。
     *
     * <p>★M9 第 18 轮★ {@code fill-missing-model} 被证明是**结构性空操作**：
     * 出厂表里 {@code m=0}（无模型）的条目 {@code c} 必然是 {@code -1}（无贴图 ⇒ 算不出色），
     * 所以「只补无模型的」= 一条都补不了（bundled 表实测可写 0 条）。
     *
     * <p>★M-7 第 34 轮★ 连「装了 Xaero ⇒ fill-missing-model」这层自动判断也删掉了 ——
     * 它等于「在最主流的机器上默认把 mod 关掉」，而实拍证明补色对 Xaero 是纯收益。
     * 现在 {@code auto} 一律解析成 {@code fill-none}，只是旧配置的兼容别名。
     */
    private String effectiveStrategy;

    /** 实际生效的策略（{@code auto} 未解析时按 {@code fill-none}）。 */
    public String strategy() {
        // 显式配置永远优先：reload 改了 colorStrategy 之后不能被上一次 applyAuto 的结果盖住
        if ("auto".equals(colorStrategy)) {
            return effectiveStrategy == null ? "fill-none" : effectiveStrategy;
        }
        return colorStrategy;
    }

    /**
     * 解析 {@code auto}。可重复调用（幂等）。
     *
     * @return 实际策略名（供日志）
     */
    public String applyAuto() {
        if ("auto".equals(colorStrategy)) {
            effectiveStrategy = "fill-none";
        } else {
            effectiveStrategy = colorStrategy;
        }
        return effectiveStrategy;
    }

    /**
     * 保护原版：{@code minecraft:} 命名空间一律不补（★第 34 轮★）。
     *
     * <p>原版有 126 个方块的 {@code mapColor} 是 {@code NONE}，全部是<b>非满方块</b>
     * （火把、铁轨、红石线、梯子、铁栏杆、花盆、作物…）。原版地图对它们的处理是
     * 「不上色，露出下面的方块」；一旦补成满格色，一片花田/铁轨会在地图上糊成色块。
     *
     * <p>实证（1.20.1 探针 CSV × 出厂表）：126 个里有 <b>123 个</b>能算出颜色，
     * 而 dev 服务端「105 条写入」全部来自这些原版方块 ⇒ 不挡掉就是纯劣化。
     * 想要连原版一起补（例如自建地图墙），把它改成 {@code false}。
     */
    public boolean protectVanilla = true;

    /** 非空时 = 白名单：只处理这些命名空间。 */
    public Set<String> enabledNamespaces = new LinkedHashSet<>();

    /** 黑名单：这些命名空间一律不动（与白名单同时存在时黑名单优先）。 */
    public Set<String> disabledNamespaces = new LinkedHashSet<>();

    /**
     * 是否写回 {@code BlockBehaviour.Properties.mapColor}。
     * <p>★已废弃，恒为 false，且<b>没有任何读方</b>★ M2 的 javap 实证（R15）：
     * {@code getMapColor} 返回 per-state 的 private final 字段，{@code mapColorProvider} 只在
     * state 构造时求值一次 ⇒ 注册表冻结后改 Settings 无效。字段只为配置向后兼容保留（R86）。
     */
    public boolean writeBackProperties = false;

    /**
     * 是否启用覆盖（两条 mixin 的实际生效开关），默认 true。
     *
     * <p>★R78 第 27 轮修正★ 这段 javadoc 以前写「关掉只做属性写回」——那是错的：
     * 属性写回本身已作废（见 {@link #writeBackProperties}），所以「关掉」原本是**空操作**。
     * 现在的落地语义是「不安装覆盖色」：mixin 在类加载期就定了，运行期只能靠「表为空 ⇒ 恒放行」
     * 来关闭，效果等价于原版行为；同时兜底线程也不再启动。
     */
    public boolean mixinGetMapColor = true;

    /**
     * M-8：是否跳过透明方块（玻璃/窗格/天窗）。
     *
     * <p>清单来自 jar 内 {@code /chromaatlas/transparent_ids.json}（离线生成，见
     * {@code _m10_transparent_ids.py}）与 {@code config/chromaatlas/transparent.json}（可手改/追加）。
     *
     * <p>★为什么默认 true★ 原版 {@code MapColor.NONE} 在**原版地图**上的语义是「这块不画」；
     * 我们补一个实心色上去 = 窗户被抹平（第 17 轮实测 200 个可见 FILLED 全是 {@code coc:window_*}）。
     * 想要「FTB Chunks 上玻璃至少有个颜色（而不是纯黑）」的场景，可以显式设 {@code false}
     * —— 那是取舍，不是默认。
     *
     * <p>手工 {@code overrides.json} 命中时仍然优先（显式指定值永远赢）。
     */
    public boolean skipTransparent = true;

    /** 启动时把每一条决策打到 info 日志（量大，默认关）。 */
    public boolean verbose = false;

    /**
     * C 档：表里没有 + 当前 mapColor 为 NONE 时，从 mod jar 的 assets 现场算色兜底。
     *
     * <p>只在「表里没有」时触发，不做全量；结果进 cache（带 jar 指纹）。默认开，
     * 关掉即退回纯人工色表（B 档）。前置条件见队列文档 R9。
     */
    public boolean runtimeFallback = true;

    /**
     * M-14：方块模型链失败时，是否退到 {@code models/item/<path>.json} 再取一次色。
     *
     * <p>★只按同名找，不猜改名★ 见 {@code AssetColorizer} 类注释。默认开：这一级只在
     * 「方块链路根本没走到贴图」时触发，命中不了就原样失败，不会改变任何已有结果。
     */
    public boolean itemFallback = true;

    /** 兜底算色的结果缓存文件（相对 config 目录）。 */
    public String runtimeCacheFile = "chromaatlas/cache.json";

    /**
     * 兜底线程每算完多少张贴图让出一次 CPU。
     *
     * <p>实测 2.3~3.3 ms/贴图（Ryzen 7 5700X）⇒ 64 张约 150~210 ms，再 sleep 20ms，
     * 稳态 CPU 占用约单核 10~15%，不会让 TPS 抖动。
     */
    public int fallbackBatchSize = 64;

    /** 每批之间 sleep 的毫秒数（限速用）。 */
    public int fallbackSleepMs = 20;

    /**
     * 启动时是否自动重绘已有地图缓存（M4b）：
     * <ul>
     *   <li>{@code off}（默认）：只能手动 {@code /chromaatlas repaint}</li>
     *   <li>{@code near}：只重绘在线玩家附近的地图</li>
     *   <li>{@code all}：重绘本维度全部地图（大服慎用）</li>
     * </ul>
     *
     * <p>★不默认开★ 重绘是「改存档里的地图字节」，属于有副作用的操作；且大服启动窗口
     * 本来就很挤，把 IO/CPU 压到启动阶段没好处。
     */
    public String repaintPolicy = "off";

    /**
     * 重绘每 tick 的像素预算（主线程分帧）。
     *
     * <p>单像素要扫 k×k 个方块列（scale 0 时 1 列），实测成本 ~2~6 µs ⇒ 4096 像素约
     * 8~25 ms/tick。想更快就调大，但别超过 {@code 20000}（一 tick 50 ms 上限）。
     */
    public int repaintPixelsPerTick = 4096;

    /** {@code near} 策略/命令默认半径（区块）。 */
    public int repaintRadiusChunks = 8;

    public boolean namespaceAllowed(String namespace) {
        if (disabledNamespaces.contains(namespace)) {
            return false;
        }
        return enabledNamespaces.isEmpty() || enabledNamespaces.contains(namespace);
    }

    public Set<String> enabledNamespacesView() {
        return Collections.unmodifiableSet(enabledNamespaces);
    }

    public Set<String> disabledNamespacesView() {
        return Collections.unmodifiableSet(disabledNamespaces);
    }
}
