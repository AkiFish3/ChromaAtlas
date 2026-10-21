package com.akira.chromaatlas.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端入口（只在物理客户端加载）。
 *
 * <p>多人客户端走不到服务端入口 ⇒ 必须自己装一份表，否则连别人的服时永远拿不到颜色。
 * fabric 侧挂的是 {@code ClientLifecycleEvents.CLIENT_STARTED}；NeoForge 侧用
 * {@code TagsUpdatedEvent}（注册表与标签就绪、且客户端已能读配置，等价时点），
 * {@link AtlasRuntime#boot} 自身幂等 ⇒ 重复触发无副作用。
 */
@Mod(value = ChromaAtlasNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class ChromaAtlasNeoForgeClient {

    private boolean booted;

    public ChromaAtlasNeoForgeClient(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        if (booted) {
            return;
        }
        Path dir = Platform.gameDir();
        if (Files.isRegularFile(dir.resolve("config/chromaatlas/probe.on"))) {
            Probe.runOnce("client", ChromaAtlasNeoForge.LOG);
        }
        AtlasRuntime.boot(dir, ChromaAtlasNeoForge.LOG);
        booted = true;
    }
}
