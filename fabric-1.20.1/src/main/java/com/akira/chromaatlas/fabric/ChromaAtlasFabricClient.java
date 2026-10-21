package com.akira.chromaatlas.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端入口（只在 EnvType.CLIENT 加载）。
 *
 * <p>两件事：
 * <ol>
 *   <li>{@link AtlasRuntime#boot} 在这里也跑一次（幂等）。集成服务端要等进世界才触发
 *       {@code SERVER_STARTING}，而<b>多人客户端</b>上根本走不到服务端入口 ⇒ 客户端必须
 *       自己装一份表，否则连别人的服时永远拿不到颜色。</li>
 *   <li>M2 探针：<b>必须在 boot 之前</b>（boot 之后 {@code getMapColor} 已经是我们的值，
 *       探针会读到污染数据）。只在存在 {@code config/chromaatlas/probe.on} 标记时跑。</li>
 * </ol>
 *
 * <p>{@code Preflight}（M2 一次性前置验证）已验证完，不再随每次启动跑 —— 它会开 500 个
 * jar 的 FileSystem，和 C 档兜底线程抢同一份 IO。
 */
public final class ChromaAtlasFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Path dir = FabricLoader.getInstance().getGameDir();
            if (Files.isRegularFile(dir.resolve("config/chromaatlas/probe.on"))) {
                Probe.runOnce("client", ChromaAtlasFabric.LOG);
            }
            AtlasRuntime.boot(dir, ChromaAtlasFabric.LOG);
        });
    }
}
