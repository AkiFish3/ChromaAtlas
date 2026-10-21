package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.core.NearestMapColor;
import com.akira.chromaatlas.core.VanillaMapColors;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NeoForge 入口。
 *
 * <p>与 fabric 壳一一对应：
 * <table>
 *   <tr><td>{@code ModInitializer#onInitialize}</td><td>构造器（{@code @Mod}）</td></tr>
 *   <tr><td>{@code ServerLifecycleEvents.SERVER_STARTING}</td><td>{@code ServerAboutToStartEvent}</td></tr>
 *   <tr><td>{@code ServerTickEvents.END_SERVER_TICK}</td><td>{@code ServerTickEvent.Post}</td></tr>
 *   <tr><td>{@code CommandRegistrationCallback}</td><td>{@code RegisterCommandsEvent}</td></tr>
 * </table>
 *
 * <p>★顺序仍然是「先探针后落色」★（R22），boot 之后 {@code getMapColor} 已是我们的值。
 */
@Mod(ChromaAtlasNeoForge.MOD_ID)
public final class ChromaAtlasNeoForge {

    public static final String MOD_ID = "chromaatlas";
    public static final Logger LOG = LoggerFactory.getLogger("ChromaAtlas");

    /** 启动后等多少个 tick 再按 {@code repaintPolicy} 自动重绘（约 10s，等区块稳定）。 */
    private static int policyDelay = 200;

    public ChromaAtlasNeoForge(IEventBus modBus) {
        LOG.info("[ChromaAtlas] core 就绪：候选 {} 色 / 原版 {} 槽位（id 0 = CLEAR 不参与推断）",
                NearestMapColor.candidateCount(), VanillaMapColors.SLOTS);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AtlasCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path dir = Platform.gameDir();
        if (Files.isRegularFile(dir.resolve("config/chromaatlas/probe.on"))) {
            Probe.runOnce("server", LOG);
        }
        AtlasRuntime.boot(dir, LOG);
        policyDelay = 200;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
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
    }
}
