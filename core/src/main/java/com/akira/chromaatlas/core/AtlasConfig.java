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
     * 补色策略（§1.5）：
     * <ul>
     *   <li>{@code fill-none}：只补当前 NONE（默认）</li>
     *   <li>{@code fill-missing-model}：只补「NONE 且离线判定无模型/贴图」⇒ 对 Xaero 无劣化</li>
     *   <li>{@code force}：连本来有色的也覆盖（调试）</li>
     * </ul>
     */
    public String colorStrategy = "fill-none";

    /** 非空时 = 白名单：只处理这些命名空间。 */
    public Set<String> enabledNamespaces = new LinkedHashSet<>();

    /** 黑名单：这些命名空间一律不动（与白名单同时存在时黑名单优先）。 */
    public Set<String> disabledNamespaces = new LinkedHashSet<>();

    /**
     * 是否写回 {@code BlockBehaviour.Properties.mapColor}。
     * <p>★已废弃，恒为 false★ M2 的 javap 实证（R15）：{@code getMapColor} 返回 per-state 的
     * private final 字段，{@code mapColorProvider} 只在 state 构造时求值一次 ⇒ 注册表冻结后改 Settings 无效。
     * 保留字段只为配置向后兼容，M8 会从默认配置里删掉。
     */
    public boolean writeBackProperties = false;

    /** 是否启用 mixin getMapColor 覆盖（关掉只做属性写回）。 */
    public boolean mixinGetMapColor = true;

    /** 启动时把每一条决策打到 info 日志（量大，默认关）。 */
    public boolean verbose = false;

    /**
     * C 档：表里没有 + 当前 mapColor 为 NONE 时，从 mod jar 的 assets 现场算色兜底。
     *
     * <p>只在「表里没有」时触发，不做全量；结果进 cache（带 jar 指纹）。默认开，
     * 关掉即退回纯人工色表（B 档）。前置条件见队列文档 R9。
     */
    public boolean runtimeFallback = true;

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
