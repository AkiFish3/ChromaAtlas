package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.NearestMapColor;
import com.akira.chromaatlas.core.VanillaMapColors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 入口。
 *
 * <p>顺序很关键：<b>先探针后落色</b>。{@code Probe} 读的是真实原版值，boot 之后
 * {@code getMapColor} 返回的就是我们的颜色，那时再探针拿到的全是污染数据。
 * 探针只在存在 {@code config/chromaatlas/probe.on} 标记文件时才跑（M2 基线复现用）。
 */
public final class ChromaAtlasFabric implements ModInitializer {

    public static final String MOD_ID = "chromaatlas";
    public static final Logger LOG = LoggerFactory.getLogger("ChromaAtlas");

    /** 启动后等待多少个 tick 再按 {@code repaintPolicy} 自动重绘（约 10s，等世界/区块稳定）。 */
    private static int policyDelay = 200;

    @Override
    public void onInitialize() {
        LOG.info("[ChromaAtlas] core 就绪：候选 {} 色 / 原版 {} 槽位（id 0 = CLEAR 不参与推断）",
                NearestMapColor.candidateCount(), VanillaMapColors.SLOTS);

        AtlasCommands.register();

        // M4b：地图缓存重绘的主线程分帧驱动 + 启动策略（默认 off，不主动动存档）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (policyDelay > 0) {
                policyDelay--;
                if (policyDelay == 0) {
                    String policy = AtlasRuntime.config().repaintPolicy;
                    if (!"off".equals(policy) && !MapRepaint.isRunning()) {
                        MapRepaint.startPolicy(server, "all".equals(policy),
                                AtlasRuntime.config().repaintRadiusChunks);
                    }
                }
            }
            MapRepaint.tick(server);
            RepaintVerify.tickMaybe(server);
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path dir = FabricLoader.getInstance().getGameDir();
            if (Files.isRegularFile(dir.resolve("config/chromaatlas/probe.on"))) {
                Probe.runOnce("server", LOG);
            }
            AtlasRuntime.boot(dir, LOG);
            policyDelay = 200;
        });
    }
}
