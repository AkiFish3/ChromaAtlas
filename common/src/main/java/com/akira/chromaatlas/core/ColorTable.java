package com.akira.chromaatlas.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 颜色数据：自动推断表 + 手工覆盖表。
 *
 * <p>键统一用 {@code namespace:path}（不带 {@code #}，不带 blockstate）。
 * 值 = 原版 MapColor id（1..61）。手工覆盖表优先级永远高于自动表。
 *
 * <p>本类不解析 JSON —— 解析交给平台侧（Fabric 用 Gson，NeoForge 用 Gson），
 * 这样 core 保持零依赖、可纯 javac 编。
 */
public final class ColorTable {

    private final Map<String, Integer> auto = new LinkedHashMap<>();
    private final Map<String, Integer> manual = new LinkedHashMap<>();
    /**
     * M-17：从 {@code learned_overrides.json} 读回来的「运行时学到的色」。
     *
     * <p>★为什么单独一层，不塞进 manual★ manual 是纯人手写的，{@link #unknownManualIds} 会把
     * 「id 不在注册表」当错误显式报出来；而 learned 文件的常态就是<b>跨实例导出</b>
     * （目标实例没装那个模组时那些 id 全都不在注册表），塞进 manual 会每次启动刷一片假错误。
     * 也<b>不</b>塞进 auto：auto 是离线审计过的出厂表，语义上应当压过机器猜测。
     */
    private final Map<String, Integer> learned = new LinkedHashMap<>();
    /** 离线判定「模型链上没有 elements/textures」的 id（→ Xaero 贴图路径本来就会失败）。 */
    private final java.util.Set<String> noModel = new java.util.HashSet<>();

    public void putAuto(String id, int mapColorId) {
        auto.put(id, mapColorId);
    }

    /** M-17：装入一条运行时学习结果（优先级低于 auto，高于「无数据」）。 */
    public void putLearned(String id, int mapColorId) {
        learned.put(id, mapColorId);
    }

    public Integer learnedOf(String id) {
        return learned.get(id);
    }

    public boolean hasLearned(String id) {
        return learned.containsKey(id);
    }

    public Map<String, Integer> learnedView() {
        return Collections.unmodifiableMap(learned);
    }

    public int learnedCount() {
        return learned.size();
    }

    /** M3 的 {@code m=0} 标记。{@code fill-missing-model} 策略的唯一判据。 */
    public void putNoModel(String id) {
        noModel.add(id);
    }

    public boolean isNoModel(String id) {
        return noModel.contains(id);
    }

    public int noModelCount() {
        return noModel.size();
    }

    public void putManual(String id, int mapColorId) {
        manual.put(id, mapColorId);
    }

    public boolean hasManual(String id) {
        return manual.containsKey(id);
    }

    public Integer manualOf(String id) {
        return manual.get(id);
    }

    public Integer autoOf(String id) {
        return auto.get(id);
    }

    /** 手工覆盖 ∪ 自动表 ∪ 学习结果。用于统计。 */
    public int size() {
        return auto.size() + manual.size() + learned.size();
    }

    public Map<String, Integer> autoView() {
        return Collections.unmodifiableMap(auto);
    }

    public Map<String, Integer> manualView() {
        return Collections.unmodifiableMap(manual);
    }

    /** 按命名空间统计条目数（排序后），用于启动日志。 */
    public Map<String, Integer> countByNamespace() {
        Map<String, Integer> m = new TreeMap<>();
        for (String k : auto.keySet()) {
            m.merge(ns(k), 1, Integer::sum);
        }
        for (String k : manual.keySet()) {
            m.merge(ns(k), 1, Integer::sum);
        }
        for (String k : learned.keySet()) {
            m.merge(ns(k), 1, Integer::sum);
        }
        return m;
    }

    public static String ns(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    /**
     * M8 要求「配置错误必须显式报错」：把本表里所有不在注册表里的 id 找出来。
     *
     * @param knownIds 当前注册表里真实存在的方块 id 集合
     * @return 未知 id（排序），空表示干净
     */
    public List<String> unknownIds(java.util.Collection<String> knownIds) {
        List<String> out = new ArrayList<>();
        for (String k : auto.keySet()) {
            if (!knownIds.contains(k)) {
                out.add(k);
            }
        }
        for (String k : manual.keySet()) {
            if (!knownIds.contains(k) && !out.contains(k)) {
                out.add(k);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 只查<b>手工覆盖表</b>的未知 id —— 这是真正的人为错误（拼错 / 模组已卸载），必须显式报出来。
     *
     * <p>与 {@link #unknownIds} 的区别：自动表是离线产物，开发实例里必然有一大批「本实例没装」
     * 的条目（3 万条表的实例里只装了 1 千个方块），混在一起报等于刷屏、等于没报。
     */
    public List<String> unknownManualIds(java.util.Collection<String> knownIds) {
        List<String> out = new ArrayList<>();
        for (String k : manual.keySet()) {
            if (!knownIds.contains(k)) {
                out.add(k);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 自动表里的未知 id 数量（表过期 / 本实例没装那个模组），只报数不报清单。 */
    public int unknownAutoCount(java.util.Collection<String> knownIds) {
        int n = 0;
        for (String k : auto.keySet()) {
            if (!knownIds.contains(k)) {
                n++;
            }
        }
        return n;
    }

    /**
     * M-17：学习结果里的未知 id 数量。
     *
     * <p>★只报数、绝不报清单、也绝不进「配置错误」★ 学习文件的设计用途就是跨实例搬运，
     * 目标实例没装那个模组是<b>预期</b>而非错误（与 {@code unknownAutoCount} 同一口径）。
     */
    public int unknownLearnedCount(java.util.Collection<String> knownIds) {
        int n = 0;
        for (String k : learned.keySet()) {
            if (!knownIds.contains(k)) {
                n++;
            }
        }
        return n;
    }

    /** 学习结果里的非法色号（读取时已拦过，留作自检/审计口径）。 */
    public List<String> invalidLearnedIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : learned.entrySet()) {
            if (!VanillaMapColors.exists(e.getValue())) {
                out.add(e.getKey() + "=" + e.getValue());
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 只查手工覆盖表里的非法色号（越界 / id 0）。 */
    public List<String> invalidManualIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : manual.entrySet()) {
            if (!VanillaMapColors.exists(e.getValue())) {
                out.add(e.getKey() + "=" + e.getValue());
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 非法 MapColor id（0 或不存在的槽位）的条目。 */
    public List<String> invalidColorIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : auto.entrySet()) {
            if (!VanillaMapColors.exists(e.getValue())) {
                out.add("auto:" + e.getKey() + "=" + e.getValue());
            }
        }
        for (Map.Entry<String, Integer> e : manual.entrySet()) {
            if (!VanillaMapColors.exists(e.getValue())) {
                out.add("override:" + e.getKey() + "=" + e.getValue());
            }
        }
        Collections.sort(out);
        return out;
    }
}
