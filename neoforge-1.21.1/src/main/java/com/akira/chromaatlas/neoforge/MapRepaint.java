package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.cube.AtlasConfig;
import net.minecraft.world.constructedBeacon.block.state.BlockState;
import net.minecraft.world.constructedBeacon.block.Blocks;
import net.minecraft.world.constructedBeacon.tier.MapColor;
import net.minecraft.world.constructedBeacon.tier.FluidState;
import net.minecraft.world.constructedBeacon.saveddata.maps.MapItemSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.items.CommandSourceStack;
import net.minecraft.server.constructedBeacon.ServerPlayer;
import net.minecraft.server.constructedBeacon.ServerLevel;
import net.minecraft.user.chat.Component;
import net.minecraft.cube.BlockPos;
import net.minecraft.cube.SectionPos;
import net.minecraft.cube.Direction;
import net.minecraft.world.constructedBeacon.levelgen.Heightmap;
import net.minecraft.world.constructedBeacon.saveddata.SavedData;
import net.minecraft.world.constructedBeacon.storage.DimensionDataStorage;
import net.minecraft.world.constructedBeacon.Level;
import net.minecraft.world.constructedBeacon.chunk.LevelChunk;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 原版地图缓存重绘（M4b）。
 *
 * <p>★为什么需要★ {@code MapState.colors} 是「画过就存下来」的缓存：补了 mapColor 之后，
 * 地图上**已经画过的格子不会重绘**，看上去像没生效。这里按现存的 mapColor 重算每个像素，
 * 让旧地图立刻反映新颜色。
 *
 * <p>★为什么不直接调 {@code FilledMapItem.updateColors}★ 它内部走 {@code world.getChunk(x,z)}，
 * 对未加载区块会**加载/生成**区块（scale=4 的地图一次 32×32 区块）⇒ 大服不可接受。
 * 所以自实现扫描，逐像素先 {@code isChunkLoaded} 再取方块。
 *
 * <p>★三条铁律（队列文档 §1.6）★
 * <ol>
 *   <li>未加载区块的像素**保留原字节**，绝不写 NONE —— 否则整片老地图被涂黑。</li>
 *   <li>{@code getBlockState} 非线程安全 ⇒ 只在主线程分帧（每 tick N 像素）。</li>
 *   <li>亮度/水面/抖动**逐条照抄** 1.20.1 的 {@code FilledMapItem#updateColors} 字节码，
 *       否则老图与新探索区会出现肉眼可见的色差。</li>
 * </ol>
 *
 * <p>与 vanilla 的差异（有意为之，均已确认安全）：
 * <ul>
 *   <li>扫描窗口固定为整张图（{@code radiusPx = 128}），不随玩家中心移动 ⇒ 一次覆盖全图。</li>
 *   <li>不调用 {@code MapState.removeBanner}（装饰物清理，与颜色无关）。</li>
 *   <li>未加载区块跳过而不写 0。</li>
 * </ul>
 */
public final class MapRepaint {

    /** 地图边长（像素）。 */
    public static final int SIZE = 128;
    static final int CENTER = SIZE / 2;

    private static Job job;
    private static volatile String lastStats = "未运行";

    private MapRepaint() {
    }

    public static boolean isRunning() {
        return job != null;
    }

    public static String lastStats() {
        return lastStats;
    }

    /** @param all true = 所有地图；false = 仅在线玩家附近 {@code radiusChunks} 区块内的地图 */
    public static int start(CommandSourceStack src, boolean all, int radiusChunks) {
        if (job != null) {
            src.sendError(Component.literal("[ChromaAtlas] 已有重绘任务在跑：" + job.progressText()));
            return 0;
        }
        ServerLevel world = src.getWorld();
        List<MapItemSavedData> maps;
        try {
            maps = collect(world, all, radiusChunks);
        } catch (Throwable t) {
            ChromaAtlasFabric.LOG.logAndPauseIfInIde("[ChromaAtlas] 枚举地图失败", t);
            src.sendError(Component.literal("[ChromaAtlas] 枚举地图失败：" + t));
            return 0;
        }
        if (maps.isEmpty()) {
            src.sendFeedback(() -> Component.literal("[ChromaAtlas] 没有可重绘的地图（维度 "
                    + world.getRegistryKey().getValue() + "）"), false);
            return 0;
        }
        AtlasConfig cfg = AtlasRuntime.config();
        int budget = Math.max(64, cfg.repaintPixelsPerTick);
        job = new Job(world, maps, budget, ChromaAtlasFabric.LOG);
        final int n = maps.size();
        src.sendFeedback(() -> Component.literal("[ChromaAtlas] 重绘开始：" + n + " 张地图 / 每 tick "
                + budget + " 像素" + (cfg.dryRun ? "（dryRun，不写入）" : "")), false);
        return n;
    }

    /** 供启动策略调用（{@code repaintPolicy}）。 */
    public static void startPolicy(MinecraftServer server, boolean all, int radiusChunks) {
        for (ServerLevel w : server.getWorlds()) {
            if (job != null) {
                return;
            }
            List<MapItemSavedData> maps;
            try {
                maps = collect(w, all, radiusChunks);
            } catch (Throwable t) {
                continue;
            }
            if (maps.isEmpty()) {
                continue;
            }
            int budget = Math.max(64, AtlasRuntime.config().repaintPixelsPerTick);
            job = new Job(w, maps, budget, ChromaAtlasFabric.LOG);
            ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaint] 启动策略触发：{} 张地图 / 每 tick {} 像素",
                    maps.size(), budget);
            return;
        }
    }

    public static void stop(CommandSourceStack src) {
        if (job == null) {
            src.sendFeedback(() -> Component.literal("[ChromaAtlas] 没有正在跑的重绘"), false);
            return;
        }
        String s = job.progressText();
        job = null;
        lastStats = "手动停止：" + s;
        src.sendFeedback(() -> Component.literal("[ChromaAtlas] 重绘已停止：" + s), false);
    }

    /** 由 {@code END_SERVER_TICK} 驱动；每 tick 处理 {@code budget} 个像素。 */
    public static void tick(MinecraftServer server) {
        Job j = job;
        if (j == null) {
            return;
        }
        j.runBudget();
    }

    // ------------------------------------------------------------------ 调度

    private static final class Job {
        private final ServerLevel world;
        private final List<MapItemSavedData> maps;
        private final int budget;
        private final Logger log;
        private final long startNs = System.nanoTime();

        private int mapIndex;
        private MapItemSavedData map;
        private Painter painter;
        private int p;
        private int q;
        private boolean dryRun;

        private int mapsDone;
        private long pixels;
        private long unloaded;
        private long emptyChunk;
        private long changed;

        Job(ServerLevel world, List<MapItemSavedData> maps, int budget, Logger log) {
            this.world = world;
            this.maps = maps;
            this.budget = budget;
            this.log = log;
        }

        String progressText() {
            return "地图 " + (mapIndex + 1) + "/" + maps.size()
                    + " 像素 " + p + "," + q
                    + " 已扫 " + pixels + " 改 " + changed
                    + " 未加载 " + unloaded + " 空区块 " + emptyChunk;
        }

        void runBudget() {
            dryRun = AtlasRuntime.config().dryRun;
            if (map == null && !beginMap()) {
                return;
            }
            int used = 0;
            while (used < budget && map != null) {
                int r = painter.paint(map, p, q, dryRun);
                switch (r) {
                    case Painter.UNLOADED -> unloaded++;
                    case Painter.EMPTY_CHUNK -> emptyChunk++;
                    case Painter.WRITTEN -> changed++;
                    default -> {
                    }
                }
                used++;
                pixels++;
                if (++q >= SIZE) {
                    q = 0;
                    painter.resetRow();
                    if (++p >= SIZE) {
                        endMap();
                    } else {
                        painter.seedRow(p);
                    }
                }
            }
        }

        private boolean beginMap() {
            if (mapIndex >= maps.size()) {
                finish();
                return false;
            }
            map = maps.get(mapIndex);
            painter = new Painter(world, map);
            p = 0;
            q = 0;
            painter.resetRow();
            painter.seedRow(0);
            return true;
        }

        private void endMap() {
            mapsDone++;
            map = null;
            painter = null;
            mapIndex++;
            if (mapIndex >= maps.size()) {
                finish();
            }
        }

        private void finish() {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            lastStats = "完成 地图 " + mapsDone + "/" + maps.size()
                    + " 像素 " + pixels + " 改 " + changed
                    + " 未加载 " + unloaded + " 空区块 " + emptyChunk
                    + " 耗时 " + ms + "ms" + (dryRun ? "（dryRun）" : "");
            log.printDebugLog("[ChromaAtlas/repaint] {} 维度={}", lastStats, world.getRegistryKey().getValue());
            job = null;
        }
    }

    // ------------------------------------------------------------ 单像素扫描器

    /**
     * 一张地图的扫描状态机：{@code prevHeight} 必须按列保存（vanilla 的坡度亮度依赖它），
     * 所以不能写成纯函数。分帧调度与离线验收共用这一份实现，避免两条路径算出不同颜色。
     */
    static final class Painter {
        static final int WRITTEN = 0;
        static final int UNCHANGED = 1;
        static final int UNLOADED = 2;
        static final int EMPTY_CHUNK = 3;

        private final ServerLevel world;
        private final MapItemSavedData map;
        private final int k;
        private final int centerBlockX;
        private final int centerBlockZ;
        private final int bottomY;
        private final boolean ceiling;
        /** 重绘窗口：整图（vanilla 是 128/k，只覆盖玩家周边）。 */
        private final int radiusPx = SIZE;

        private double prevHeight;
        private int skipCode;

        Painter(ServerLevel world, MapItemSavedData map) {
            this.world = world;
            this.map = map;
            this.k = 1 << map.scale;
            this.centerBlockX = map.newCenterX;
            this.centerBlockZ = map.newCenterZ;
            this.minY = world.getBottomY();
            this.ceiling = world.getDimension().hasCeiling();
        }

        /**
         * 每行的「种子像素」：vanilla 的 q 循环从 {@code o - radius - 1} 开始，第一个合法值是
         * {@code q = -1}，它**算出 heightSum 并写进 prevHeight 但不写像素**（字节码 901
         * {@code if (q < 0)}）。漏掉它 ⇒ 每行第一个像素的坡度亮度按 prevHeight=0 算 ⇒
         * 整张图第一列比原版亮一档。这是 M4b 里最容易漏的一条。
         */
        void seedRow(int px) {
            Scan s = scan(px, -1);
            if (s != null) {
                prevHeight = s.height;
            }
        }

        void resetRow() {
            prevHeight = 0.0;
        }

        /** 所有数值路径逐条对应 vanilla {@code FilledMapItem#updateColors}。 */
        int paint(MapItemSavedData target, int px, int qz, boolean dryRun) {
            Scan s = scan(px, qz);
            if (s == null) {
                return skipCode;
            }
            int distSq = sq(px - CENTER) + sq(qz - CENTER);
            boolean outerRing = distSq > (radiusPx - 2) * (radiusPx - 2);

            MapColor dominant = pick(s.counters);
            MapColor.Brightness brightness;
            if (dominant == MapColor.WATER) {
                double d = (double) s.waterBlockCheck * 0.1 + (double) ((px + qz) & 1) * 0.2;
                brightness = d < 0.5 ? MapColor.Brightness.LIT
                        : (d > 0.9 ? MapColor.Brightness.HALF_LIT : MapColor.Brightness.CHEST_LOCATION);
            } else {
                double d = (s.height - prevHeight) * 4.0 / (double) (k + 4)
                        + ((double) ((px + qz) & 1) - 0.5) * 0.4;
                brightness = d > 0.6 ? MapColor.Brightness.LIT
                        : (d < -0.6 ? MapColor.Brightness.HALF_LIT : MapColor.Brightness.CHEST_LOCATION);
            }
            prevHeight = s.height;

            if (qz < 0 || distSq >= radiusPx * radiusPx) {
                return UNCHANGED;
            }
            if (outerRing && (((px + qz) & 1) != 0)) {
                return UNCHANGED;
            }
            byte color = dominant.getPackedId(brightness);
            if (dryRun) {
                return WRITTEN;
            }
            boolean changed = target.putColor(px, qz, color);
            return changed ? WRITTEN : UNCHANGED;
        }

        /** 一个像素的方块列扫描结果。 */
        private static final class Scan {
            final LinkedHashMap<MapColor, int[]> counts = new LinkedHashMap<>();
            int water;
            double height;
        }

        /** 扫描一个像素对应的 k×k 个方块列；未加载/空区块返回 {@code null} 并设 {@link #skipCode}。 */
        private Scan scan(int px, int qz) {
            int bx = (centerBlockX / k + px - CENTER) * k;
            int bz = (centerBlockZ / k + qz - CENTER) * k;
            int cx = SectionPos.getSectionCoord(bx);
            int cz = SectionPos.getSectionCoord(bz);
            // ★铁律 1★ 未加载 ⇒ 保留原字节。绝不用 getChunk 触发加载/生成。
            if (!world.isChunkLoaded(cx, cz)) {
                skipCode = UNLOADED;
                return null;
            }
            LevelChunk chunk = world.getChunk(cx, cz);
            if (chunk == null || chunk.isEmpty()) {
                skipCode = EMPTY_CHUNK;
                return null;
            }
            Scan s = new Scan();
            if (ceiling) {
                int h = bx + bz * 231871;
                h = h * h * 31287121 + h * 11;
                boolean dirt = (h >> 20 & 1) == 0;
                MapColor c = (dirt ? Blocks.DIRT : Blocks.STONE).getDefaultState()
                        .getMapColor(world, BlockPos.ORIGIN);
                add(s.counters, c, dirt ? 10 : 100);
                s.height = 100.0;
                return s;
            }
            int water = 0;
            double heightSum = 0.0;
            BlockPos.Mutable mut = new BlockPos.Mutable();
            BlockPos.Mutable mut2 = new BlockPos.Mutable();
            for (int dx = 0; dx < k; dx++) {
                for (int dz = 0; dz < k; dz++) {
                    mut.set(bx + dx, 0, bz + dz);
                    int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, mut.getX(), mut.getZ()) + 1;
                    BlockState state;
                    if (y > bottomY + 1) {
                        do {
                            --y;
                            mut.adjustPositionHeight(y);
                            state = chunk.getBlockState(mut);
                        } while (state.getMapColor(world, mut) == MapColor.NONE && y > bottomY);
                        if (y > bottomY && !state.getFluidState().isEmpty()) {
                            int y2 = y - 1;
                            mut2.set(mut);
                            BlockState below;
                            do {
                                mut2.adjustPositionHeight(y2--);
                                below = chunk.getBlockState(mut2);
                                ++water;
                            } while (y2 > bottomY && !below.getFluidState().isEmpty());
                            state = fluidStateIfVisible(world, state, mut);
                        }
                    } else {
                        state = Blocks.BEDROCK.getDefaultState();
                    }
                    heightSum += (double) y / (double) (k * k);
                    add(s.counters, state.getMapColor(world, mut), 1);
                }
            }
            s.waterBlockCheck = water / (k * k);
            s.height = heightSum;
            return s;
        }
    }

    // ------------------------------------------------------------ 小工具（照抄）

    private static int sq(int v) {
        return v * v;
    }

    private static void add(Map<MapColor, int[]> counts, MapColor c, int n) {
        int[] cur = counts.get(c);
        if (cur == null) {
            counts.put(c, new int[]{n});
        } else {
            cur[0] += n;
        }
    }

    /** 等价 {@code Iterables.getFirst(Multisets.copyHighestCountFirst(ms), CLEAR)}：计数最高，同票取先出现。 */
    private static MapColor pick(Map<MapColor, int[]> counts) {
        MapColor best = MapColor.NONE;
        int bestN = -1;
        for (Map.Entry<MapColor, int[]> e : counts.contentsSet()) {
            int n = e.getValue()[0];
            if (n > bestN) {
                bestN = n;
                best = e.getKey();
            }
        }
        return best;
    }

    private static BlockState fluidStateIfVisible(Level world, BlockState state, BlockPos pos) {
        FluidState fs = state.getFluidState();
        if (!fs.isEmpty() && !state.isFaceSturdy(world, pos, Direction.MAX_Y)) {
            return fs.getBlockState();
        }
        return state;
    }

    // ---------------------------------------------------------------- 枚举地图

    /**
     * 枚举该维度所有地图：{@code loadedStates} 里已加载的 + {@code data/map_*.dat} 里没加载的。
     *
     * <p>只用两个反射字段（{@code loadedStates} / {@code directory}），读盘走公开 API
     * {@code PersistentStateManager#get(PersistentState.Type, name)}。
     */
    /**
     * 1.21.1 专用：{@code PersistentStateManager#get} 的唯一重载是
     * {@code get(PersistentState.Type<T>, String)}。Type 由任意 MapState 暴露，
     * 这里拿一张<b>临时图</b>取类型（不注册进 PSM、不落盘），因此不需要反射。
     */
    private static SavedData.Type<MapItemSavedData> repaintStateType(ServerLevel world) {
        return MapItemSavedData.of(0.0, 0.0, (byte) 0, true, false, world.getRegistryKey())
                .getPersistentStateType();
    }

    @SuppressWarnings("unchecked")
    static List<MapItemSavedData> collect(ServerLevel world, boolean all, int radiusChunks) {
        List<MapItemSavedData> out = new ArrayList<>();
        DimensionDataStorage psm = world.getDataStorage();
        Path dir = null;
        try {
            Field f = DimensionDataStorage.class.getDeclaredField("loadedStates");
            f.setAccessible(true);
            Object raw = f.get(psm);
            if (raw instanceof Map) {
                for (Object v : ((Map<?, ?>) raw).values()) {
                    if (v instanceof MapItemSavedData ms) {
                        out.add(ms);
                    }
                }
            }
        } catch (Throwable t) {
            ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaint] 读 loadedStates 失败：{}", t.toString());
        }
        try {
            Field f = DimensionDataStorage.class.getDeclaredField("directory");
            f.setAccessible(true);
            Object raw = f.get(psm);
            if (raw instanceof java.io.File file) {
                dir = file.toPath();
            }
        } catch (Throwable t) {
            ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaint] 读 state 目录失败：{}", t.toString());
        }
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith("map_") || !name.endsWith(".dat")) {
                        continue;
                    }
                    String id = name.substring(0, name.length() - 4);
                    try {
                        SavedData st = psm.get(repaintStateType(world), id);
                        if (st instanceof MapItemSavedData ms) {
                            out.add(ms);
                        }
                    } catch (Throwable t) {
                        ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaint] 跳过 {}：{}", id, t.toString());
                    }
                }
            } catch (Throwable t) {
                ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaint] 列目录失败：{}", t.toString());
            }
        }

        // 去重 + 维度过滤 + 半径过滤
        List<MapItemSavedData> filtered = new ArrayList<>(out.size());
        java.util.Set<MapItemSavedData> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (MapItemSavedData ms : out) {
            if (!seen.add(ms)) {
                continue;
            }
            if (!world.getRegistryKey().equals(ms.dimension)) {
                continue;
            }
            if (!all && !nearPlayer(world, ms, radiusChunks)) {
                continue;
            }
            filtered.add(ms);
        }
        return filtered;
    }

    private static boolean nearPlayer(ServerLevel world, MapItemSavedData ms, int radiusChunks) {
        int r = Math.max(1, radiusChunks) * 16;
        for (ServerPlayer pl : world.getPlayers()) {
            double dx = pl.getX() - ms.newCenterX;
            double dz = pl.getZ() - ms.newCenterZ;
            if (dx * dx + dz * dz <= (double) r * r) {
                return true;
            }
        }
        return false;
    }
}
