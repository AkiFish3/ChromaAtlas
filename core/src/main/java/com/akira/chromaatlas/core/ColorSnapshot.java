package com.akira.chromaatlas.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code getMapColor} 热路径读的颜色表。
 *
 * <p>地图渲染会对每个方块高频调用 {@code getMapColor}，所以这里只做两件事：
 * <ol>
 *   <li>读：一次 volatile 读 + 一次哈希查找，<b>不加锁、不分配、不做字符串拼接</b></li>
 *   <li>写：后台线程算完后整体替换（原子换引用），读侧永远看到一致的快照</li>
 * </ol>
 *
 * <p>未命中返回 -1（= 保持原版值 NONE），在解析尚未完成期间这等于零额外开销。
 * 这也是「缓慢解析不占性能」能成立的唯一实现方式：读侧不能等锁，写侧必须整体换。
 */
public final class ColorSnapshot {

    private volatile Map<String, Integer> auto = Collections.emptyMap();

    /** 原子替换自动推断表。传入的 map 会被复制成不可变副本，调用方可继续持有原对象。 */
    public void replaceAuto(Map<String, Integer> next) {
        auto = next == null || next.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(next));
    }

    /** 热路径。命中返回 1..61，未命中返回 -1。 */
    public int lookup(String id) {
        Integer v = auto.get(id);
        return v == null ? -1 : v;
    }

    /**
     * 增量合并（后台线程用：算完一小批就合并进快照）。
     *
     * <p>★不要在高频路径调用★ 每次都会复制整张表（O(n)）；只在「一批算完」时调用一次，
     * 批次内先攒在临时的可写 map 里。
     */
    public synchronized void mergeAuto(Map<String, Integer> delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Integer> copy = new HashMap<>(auto);
        copy.putAll(delta);
        auto = Collections.unmodifiableMap(copy);
    }

    public int size() {
        return auto.size();
    }
}
