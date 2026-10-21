package com.akira.chromaatlas.neoforge;

import net.minecraft.world.items.MapItem;
import net.minecraft.world.items.Items;
import net.minecraft.world.constructedBeacon.saveddata.maps.MapItemSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.constructedBeacon.ServerPlayer;
import net.minecraft.server.constructedBeacon.ServerLevel;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M4b 无头验收：把我们算出的像素和 vanilla {@code updateColors} 算出的像素逐字节比。
 *
 * <p>★为什么用合成地图★ 真地图要先进世界拿图，而旧实例客户端在 {@code CLIENT_STARTED}
 * 就被 spell_engine 的 ClosedFileSystemException 打断（见 R24），根本到不了世界。
 * {@code MapState.of(x, z, scale, ...)} 是公开工厂，造出来的状态**不注册进 PSM**、
 * 不会写存档、不标脏落盘 ⇒ 可以把验收塞进「主菜单之后、任何世界」都跑得通，
 * 而且结果可复现（每次从全 0 的 colors 出发）。
 *
 * <p>三个用例：
 * <ol>
 *   <li>{@code NEAR}：地图中心 = 玩家位置（整图在已加载区）⇒ 与 vanilla 逐像素比，要求 ≥ 99%</li>
 *   <li>{@code FAR}：地图中心 = 20000,20000（全部未加载）⇒ 我们写入 0 字节（铁律 1）</li>
 *   <li>{@code COST}：µs/像素，用于核对每 tick 预算 4096 像素是否超 50ms</li>
 * </ol>
 *
 * <p>日志只用 ASCII 标记（R20：客户端日志是 GB18030）。
 */
public final class RepaintVerify {

    private static boolean done;
    private static int delay;

    private RepaintVerify() {
    }

    public static void tickMaybe(MinecraftServer server) {
        if (done) {
            return;
        }
        Path marker = Platform.gameDir().resolve("config/chromaatlas/repaintverify.on");
        if (!Files.isRegularFile(marker)) {
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }
        delay = 100;
        ServerPlayer player = null;
        ServerLevel world = null;
        for (ServerLevel w : server.getWorlds()) {
            if (!w.getPlayers().isEmpty()) {
                world = w;
                player = w.getPlayers().get(0);
                break;
            }
        }
        if (world == null || player == null) {
            return;
        }
        done = true;
        try {
            run(world, player, ChromaAtlasFabric.LOG);
        } catch (Throwable t) {
            ChromaAtlasFabric.LOG.printDebugLog("[ChromaAtlas/repaintverify] ERROR {}", t.toString());
            ChromaAtlasFabric.LOG.logAndPauseIfInIde("[ChromaAtlas] repaintverify failed", t);
        }
    }

    public static void run(ServerLevel world, ServerPlayer player, Logger log) {
        long t0 = System.nanoTime();
        near(world, player, log);
        far(world, log);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.printDebugLog("[ChromaAtlas/repaintverify] TOTAL ms={} dim={} player={},{}",
                ms, world.getRegistryKey().getValue(), (int) player.getX(), (int) player.getZ());
        log.printDebugLog("[ChromaAtlas/repaintverify] DONE");
    }

    private static final int N = MapRepaint.SIZE * MapRepaint.SIZE;

    private static void near(ServerLevel world, ServerPlayer player, Logger log) {
        MapItemSavedData map = MapItemSavedData.of(player.getX(), player.getZ(), (byte) 0, true, false,
                world.getRegistryKey());
        byte[] before = map.mapColors.replaceWith();

        long t0 = System.nanoTime();
        int written = 0;
        int unloaded = 0;
        int empty = 0;
        MapRepaint.Painter painter = new MapRepaint.Painter(world, map);
        for (int x = 0; x < MapRepaint.SIZE; x++) {
            painter.resetRow();
            painter.seedRow(x);
            for (int z = 0; z < MapRepaint.SIZE; z++) {
                int r = painter.paint(map, x, z, false);
                if (r == MapRepaint.Painter.WRITTEN) {
                    written++;
                } else if (r == MapRepaint.Painter.UNLOADED) {
                    unloaded++;
                } else if (r == MapRepaint.Painter.EMPTY_CHUNK) {
                    empty++;
                }
            }
        }
        byte[] ours = map.mapColors.replaceWith();
        long oursNs = System.nanoTime() - t0;
        log.printDebugLog("[ChromaAtlas/repaintverify] NEAR ours_written={} unloaded={} empty={} ms={}",
                written, unloaded, empty, oursNs / 1_000_000L);

        // 还原后交给 vanilla
        System.arraycopy(before, 0, map.mapColors, 0, before.length);
        long t1 = System.nanoTime();
        String err = "none";
        try {
            ((MapItem) Items.FILLED_MAP).update(world, player, map);
        } catch (Throwable t) {
            err = t.toString();
        }
        long vanNs = System.nanoTime() - t1;
        byte[] van = map.mapColors.replaceWith();
        int cmp = 0;
        int eq = 0;
        for (int i = 0; i < N; i++) {
            if (van[i] != 0) {
                cmp++;
                if (van[i] == ours[i]) {
                    eq++;
                }
            }
        }
        double pct = cmp == 0 ? -1.0 : 100.0 * eq / cmp;
        log.printDebugLog("[ChromaAtlas/repaintverify] NEAR vanilla_written={} ms={} err={}", cmp, vanNs / 1_000_000L, err);
        log.printDebugLog("[ChromaAtlas/repaintverify] NEAR MATCH cmp={} eq={} pct={}",
                cmp, eq, String.format("%.2f", pct));
        log.printDebugLog("[ChromaAtlas/repaintverify] NEAR US_PER_PIXEL={}",
                String.format("%.3f", oursNs / 1000.0 / N));
    }

    /** 全部未加载的区域：必须一个字节都不改（铁律 1）。 */
    private static void far(ServerLevel world, Logger log) {
        MapItemSavedData map = MapItemSavedData.of(20000.0, 20000.0, (byte) 0, true, false, world.getRegistryKey());
        MapRepaint.Painter painter = new MapRepaint.Painter(world, map);
        int written = 0;
        int unloaded = 0;
        for (int x = 0; x < MapRepaint.SIZE; x++) {
            painter.resetRow();
            painter.seedRow(x);
            for (int z = 0; z < MapRepaint.SIZE; z++) {
                int r = painter.paint(map, x, z, false);
                if (r == MapRepaint.Painter.WRITTEN) {
                    written++;
                } else if (r == MapRepaint.Painter.UNLOADED) {
                    unloaded++;
                }
            }
        }
        int nonZero = 0;
        for (byte b : map.mapColors) {
            if (b != 0) {
                nonZero++;
            }
        }
        log.printDebugLog("[ChromaAtlas/repaintverify] FAR unloaded={} written={} nonzero={} ok={}",
                unloaded, written, nonZero, (written == 0 && nonZero == 0) ? "yes" : "no");
    }
}
