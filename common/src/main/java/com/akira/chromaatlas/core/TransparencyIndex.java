package com.akira.chromaatlas.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M-8：透明方块黑名单（只读 id 集合）。
 *
 * <p>★为什么需要它★ 玻璃/窗格/天窗在原版语义里 {@code mapColor == NONE} —— 地图上<b>不画</b>。
 * 我们在 {@code fill-none}/{@code force} 下会给所有 NONE 补 61 色中的一个实心色，
 * 于是窗户在地图上变成一块实色（第 17 轮实测：玩家附近 200 个可见 FILLED 全是 {@code coc:window_*}）。
 * 那不是「补全」，是把透明件抹平 ⇒ 真降级。
 *
 * <p>★为什么离线出清单而不是运行时判★ 判透明只有两条路：
 * <ol>
 *   <li>{@code ItemBlockRenderTypes.getChunkRenderType(state)} —— 只有客户端有，且启动早期
 *       层表还没填，服务端根本没有这个类（要额外隔离 + 未验证的客户端 API，代价大）</li>
 *   <li><b>离线出 id 清单</b>（本类吃的就是它）—— 见 {@code _m10_transparent_ids.py}：
 *       C 队列脚本的 {@code TRANSLUCENT_IDS} + 各 jar 模型 {@code render_type=translucent}
 *       + 原版玻璃族 + coc/minia 的 {@code window_/glass/skylight} 命名规则</li>
 * </ol>
 *
 * <p>★已经证伪的两条路★（别再试）：贴图 alpha 覆盖率（{@code *pane*} 中位 1.000，玻璃是
 * 不透明像素画的质感）、模型几何（窗框也是满方块）。
 */
public final class TransparencyIndex {

    public static final TransparencyIndex EMPTY = new TransparencyIndex(Collections.<String>emptySet());

    private final Set<String> ids;

    private TransparencyIndex(Set<String> ids) {
        this.ids = ids;
    }

    public static TransparencyIndex of(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return EMPTY;
        }
        return new TransparencyIndex(Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
    }

    /** 命中 ⇒ 不要给它补色。 */
    public boolean shouldSkip(String id) {
        return ids.contains(id);
    }

    public int size() {
        return ids.size();
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }
}
