package com.akira.chromaatlas.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 不解引用 world 的取色入口 —— 全模块唯一实现。
 *
 * <p>★M-10 / R107★ {@code BlockState#getMapColor(BlockView, BlockPos)} 在 1.21.1 客户端上
 * 会被第三方 mixin 注入（bountifulfares 的 {@code handler$dne001$...}），它直接
 * {@code world.getBlockState(pos)}；传 {@code null} 必 NPE，而外层的
 * {@code catch (Throwable) { continue; }} 会把整轮扫描吞成「注册表 0」。
 *
 * <p>因此凡是<b>不需要世界上下文</b>的取色（启动基线、探针、预检）都必须走这里：
 * <ol>
 *   <li>{@link #reflect(BlockState)} —— 反射读 state 里 per-state 的 {@code MapColor} 字段。
 *       1.20.1 的 {@code getMapColor} 本身就返回它，语义等价且不经过任何 mixin；</li>
 *   <li>反射不可用时退 {@link #blockLevel(Block)} —— 方块级 {@code getDefaultMapColor()}，同样不吃 world。</li>
 * </ol>
 *
 * <p>★不要在这里加缓存★：state 对象在 reload 期间会被写色，缓存会让基线自我污染（R22）。
 */
public final class RawColors {

    /** {@link #reflect} 成功（读到了 per-state 字段）。 */
    public static final int VIA_REFLECT = 0;
    /** 退到方块级 {@code getDefaultMapColor()}。 */
    public static final int VIA_BLOCK = 1;

    private static volatile boolean probed;
    private static java.lang.reflect.Field stateColorField;

    private RawColors() {
    }

    /**
     * 反射读 per-state 的 {@code MapColor} 字段。
     *
     * @return 色号；读不到返回 <b>-1</b>（与「0 = MapColor.NONE」区分开，调用方自行兜底）
     */
    public static int reflect(BlockState st) {
        java.lang.reflect.Field f = field();
        if (f == null) {
            return -1;
        }
        try {
            Object v = f.get(st);
            if (v instanceof MapColor) {
                return ((MapColor) v).id;
            }
        } catch (Throwable ignored) {
            // 单个 state 读不到 ⇒ 调用方走方块级兜底
        }
        return -1;
    }

    /**
     * 方块级兜底色（不吃 world）。
     *
     * @return 色号；异常或 null 一律 0（= NONE）
     */
    public static int blockLevel(Block block) {
        try {
            MapColor c = block.defaultMapColor();
            return c == null ? 0 : c.id;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 两级取色：先反射，失败退方块级。
     *
     * @param viaOut 长度 ≥1 的 int[]，写入 {@link #VIA_REFLECT} / {@link #VIA_BLOCK}；
     *               传 {@code null} 表示不关心（避免热路径无谓分配时也可复用同一个数组）
     */
    public static int color(BlockState st, Block block, int[] viaOut) {
        int r = reflect(st);
        if (r >= 0) {
            if (viaOut != null && viaOut.length > 0) {
                viaOut[0] = VIA_REFLECT;
            }
            return r;
        }
        if (viaOut != null && viaOut.length > 0) {
            viaOut[0] = VIA_BLOCK;
        }
        return blockLevel(block);
    }

    /** {@link #color(BlockState, Block, int[])} 的无状态简写。 */
    public static int color(BlockState st, Block block) {
        return color(st, block, null);
    }

    /** per-state {@code mapColor} 字段（懒查一次；字段名在各平台上都被混淆，所以按<b>类型</b>找）。 */
    public static java.lang.reflect.Field field() {
        if (!probed) {
            probed = true;
            try {
                Class<?> c = BlockState.class;
                while (c != null && c != Object.class && stateColorField == null) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                || f.getType() != MapColor.class) {
                            continue;
                        }
                        f.setAccessible(true);
                        stateColorField = f;
                        break;
                    }
                    c = c.getSuperclass();
                }
            } catch (Throwable ignored) {
                stateColorField = null;
            }
        }
        return stateColorField;
    }

    /** 反射路径在当前平台是否可用（日志自证用，不靠猜）。 */
    public static boolean reflectAvailable() {
        return field() != null;
    }
}
