package com.akira.chromaatlas.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 实际生效的颜色（M4 应用层）。
 *
 * <p>★为什么不写回 {@code Properties.mapColor}★ M2 的 javap 实证（R15）：
 * {@code AbstractBlockState#getMapColor} 直接返回 per-state 的 {@code private final} 字段，
 * 而 {@code Settings.mapColorProvider} 只在 state 构造时求值一次 ⇒ 注册表冻结后改 Settings 完全无效。
 * 所以本 mod 只有一条应用路径：mixin 拦 {@code getMapColor}，命中这里就返回。
 *
 * <p>★热路径口径★ 以 {@link Block} 为键（Block 不重写 equals/hashCode ⇒ 身份哈希），
 * 一次 volatile 读 + 一次哈希查找，<b>不拼字符串、不加锁、不分配</b>。
 * 用 id 字符串做键会在地图渲染里给每个像素造一个 String，是上一版设计留下的坑。
 */
public final class AppliedColors {

    /** id → MapColor 实例（id 0 = CLEAR 也放进去，但永不作为写入值）。 */
    private static final MapColor[] BY_ID = new MapColor[64];

    private static volatile Map<Block, MapColor> BY_BLOCK = Collections.emptyMap();
    private static volatile Map<Block, Integer> ID_BY_BLOCK = Collections.emptyMap();
    /** 注册表全量 id → Block，兜底线程按 id 回推时用。 */
    private static volatile Map<String, Block> BLOCK_BY_ID = Collections.emptyMap();

    // ---- 启动统计（给 /chromaatlas status）----
    public static volatile int applied;
    public static volatile int appliedManual;
    public static volatile int appliedAuto;
    /** ★M-17★ 命中 learned_overrides.json 的条数（单独计数，日志才看得出导出文件真的生效了）。 */
    public static volatile int appliedLearned;
    public static volatile int skippedHasColor;
    public static volatile int skippedNamespace;
    public static volatile int skippedNoData;
    public static volatile int skippedInvalid;
    public static volatile int dryRunPlanned;
    public static volatile int verifySampled;
    public static volatile int verifyOk;
    public static volatile int verifyMismatch;
    public static volatile boolean dryRun;

    static {
        buildIdTable();
    }

    private AppliedColors() {
    }

    /**
     * 反射扫描 {@code MapColor} 的静态字段建 id→实例表。
     *
     * <p>不用 {@code COLORS} 私有数组：1.20.1 里它是 private，而静态 final 的反射读
     * 在不同 JVM/混淆组合下更容易踩坑；扫同名类型字段是纯公开信息。
     */
    private static void buildIdTable() {
        try {
            for (Field f : MapColor.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != MapColor.class) {
                    continue;
                }
                try {
                    MapColor c = (MapColor) f.get(null);
                    if (c != null && c.id >= 0 && c.id < BY_ID.length) {
                        BY_ID[c.id] = c;
                    }
                } catch (Throwable ignored) {
                    // 单个字段读不到不影响其它
                }
            }
        } catch (Throwable ignored) {
            // 反射全挂 ⇒ BY_ID 留空 ⇒ lookup 恒 null ⇒ 保持原版，降级而不是崩
        }
    }

    /** id→MapColor；越界或未使用槽位返回 null。 */
    public static MapColor byId(int id) {
        return id > 0 && id < BY_ID.length ? BY_ID[id] : null;
    }

    public static MapColor[] table() {
        return BY_ID;
    }

    /** 装表（boot / reload 各调一次）。dryRun ⇒ 只记计划条数，不换引用。 */
    public static void install(Map<Block, Integer> planned, Map<String, Block> blockById,
                               boolean isDryRun, Logger log) {
        BLOCK_BY_ID = Collections.unmodifiableMap(new HashMap<>(blockById));
        dryRun = isDryRun;
        if (isDryRun) {
            // ★R85 第 27 轮★ 以前这里直接 return，BY_BLOCK 保持上一次 install 的内容 ⇒
            // 「跑到一半改成 dryRun=true 再 reload」时旧覆盖色仍然生效（dryRun 只是日志好看）。
            // dryRun 的语义是「不写入」，所以必须把已装的那份也清掉。
            int had = BY_BLOCK.size();
            BY_BLOCK = Collections.emptyMap();
            ID_BY_BLOCK = Collections.emptyMap();
            applied = 0;
            dryRunPlanned = planned.size();
            log.info("[ChromaAtlas] dryRun=true ⇒ 计划写入 {} 条，实际写入 0 条{}",
                    planned.size(), had > 0 ? "（已清除上一轮的 " + had + " 条覆盖色）" : "");
            return;
        }
        Map<Block, MapColor> colors = new HashMap<>(Math.max(16, planned.size() * 2));
        Map<Block, Integer> ids = new HashMap<>(Math.max(16, planned.size() * 2));
        int dropped = 0;
        for (Map.Entry<Block, Integer> e : planned.entrySet()) {
            MapColor mc = byId(e.getValue());
            if (mc == null) {
                dropped++;
                continue;
            }
            colors.put(e.getKey(), mc);
            ids.put(e.getKey(), e.getValue());
        }
        BY_BLOCK = Collections.unmodifiableMap(colors);
        ID_BY_BLOCK = Collections.unmodifiableMap(ids);
        applied = ids.size();
        if (dropped > 0) {
            log.info("[ChromaAtlas] {} 条色号找不到对应 MapColor 实例，已丢弃", dropped);
        }
    }

    /** 兜底线程每批算完后按 id 合并（O(n) 复制，只在批末调用，不在热路径）。 */
    public static synchronized void mergeIds(Map<String, Integer> delta) {
        if (delta == null || delta.isEmpty() || dryRun) {
            return;
        }
        Map<String, Block> idx = BLOCK_BY_ID;
        Map<Block, MapColor> colors = new HashMap<>(BY_BLOCK.size() + delta.size());
        Map<Block, Integer> ids = new HashMap<>(ID_BY_BLOCK.size() + delta.size());
        colors.putAll(BY_BLOCK);
        ids.putAll(ID_BY_BLOCK);
        int added = 0;
        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            Block b = idx.get(e.getKey());
            MapColor mc = byId(e.getValue());
            if (b == null || mc == null) {
                continue;
            }
            colors.put(b, mc);
            ids.put(b, e.getValue());
            added++;
        }
        BY_BLOCK = Collections.unmodifiableMap(colors);
        ID_BY_BLOCK = Collections.unmodifiableMap(ids);
        applied = ids.size();
        if (added > 0) {
            appliedAuto += added;
        }
    }

    /** ★热路径★ 命中返回覆盖色，未命中返回 null（= 走原版逻辑）。绝不抛。 */
    public static MapColor of(BlockState state) {
        Map<Block, MapColor> m = BY_BLOCK;
        return m.isEmpty() ? null : m.get(state.getBlock());
    }

    /** 已被本 mod 改写的色号；未改写返回 -1。 */
    public static int idOf(Block block) {
        Integer v = ID_BY_BLOCK.get(block);
        return v == null ? -1 : v;
    }

    public static int size() {
        return BY_BLOCK.size();
    }
}
