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
    /** 离线判定「模型链上没有 elements/textures」的 id（→ Xaero 贴图路径本来就会失败）。 */
    private final java.util.Set<String> noModel = new java.util.HashSet<>();

    public void putAuto(String id, int mapColorId) {
        auto.put(id, mapColorId);
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

    /** 手工覆盖 ∪ 自动表。用于统计。 */
    public int size() {
        return auto.size() + manual.size();
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
