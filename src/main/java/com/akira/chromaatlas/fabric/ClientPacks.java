package com.akira.chromaatlas.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Optional;

/**
 * 客户端资源包来源（M-15）：把 {@code ResourceManager} 接成 {@link AssetColorizer.PackSource}。
 *
 * <p>★为什么必须有这一层★ {@code AssetColorizer} 原来只认 {@code FabricLoader#getModContainer}。
 * KubeJS 的 {@code kubejs/assets} 不是 mod ⇒ 容器恒空 ⇒ {@code coc}/{@code minia}
 * （测试实例里 9635 + 2704 个 NONE）在运行时整片 {@code NO_CONTAINER}，
 * 客户端明明画得出来却算不出色。资源包不是「锦上添花」，它是这两个命名空间的<b>唯一</b>来源。
 *
 * <p>★为什么要单独一个类★ 专用服务端没有 {@code net.minecraft.client.MinecraftClient}
 * （客户端类是客户端 jar 专有）。本类只在 {@link #install()} 里被 {@link ChromaAtlasFabricClient}
 * 调用，服务端永远不会加载它；如果把这段直接写进 {@code AssetColorizer}，服务端类加载就炸。
 *
 * <p>★三个平台只有三处差异★（见 {@code _m27_port_m15.py}）
 * {@code MinecraftClient}→{@code Minecraft}、{@code Identifier.of}→
 * {@code ResourceLocation.fromNamespaceAndPath}、{@code getInputStream}→{@code open}。
 *
 * <p>★每次调用都重新取 ResourceManager★ 客户端 reload 后 {@code MinecraftClient} 返回的是
 * 同一个 {@code ReloadableResourceManager}，但缓存实例没有必要 —— 取一次是 getter，成本可忽略，
 * 而缓存反而会在「资源包换了」之后继续读旧对象（R24 同族的另一面：不要替别的组件持有生命周期）。
 */
public final class ClientPacks implements AssetColorizer.PackSource {

    private ClientPacks() {
    }

    /**
     * 在客户端入口调用一次（幂等）。服务端调用为空操作 —— 双保险：
     * 入口本来就只在客户端加载，这里再判一次环境，避免有人把 install 抄到服务端入口。
     */
    public static void install() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        AssetColorizer.installPackSource(new ClientPacks());
        ChromaAtlasFabric.LOG.info("[ChromaAtlas] 资源来源：客户端 ResourceManager 优先（含资源包覆盖），未命中回退 mod 容器");
    }

    @Override
    public InputStream open(String ns, String rel) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) {
                return null;
            }
            ResourceManager rm = mc.getResourceManager();
            if (rm == null) {
                return null; // 资源还没 reload 完 ⇒ 当未命中，让调用方回退 jar
            }
            Optional<Resource> r = rm.getResource(Identifier.of(ns, rel));
            if (r == null || !r.isPresent()) {
                return null;
            }
            return r.get().getInputStream();
        } catch (Throwable t) {
            // reload 竞态 / 资源包被关 ⇒ 未命中。绝不把异常抛给上层（上层会记成 ERROR 掩盖真原因）
            return null;
        }
    }
}
