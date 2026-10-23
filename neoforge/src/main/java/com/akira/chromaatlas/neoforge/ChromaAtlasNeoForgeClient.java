package com.akira.chromaatlas.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
 *
 * <p>★M-10 第 31 轮★ 第 30 轮实机发现：NeoForge 客户端在 {@code TagsUpdatedEvent} 上
 * 扫到 <b>0 个方块</b>（表载入正常、策略正常、写入 0），而同一 jar 的服务端 105 条正常。
 * 该事件在这一局里触发于 integrated server 启动前 0.3s，因此「注册表此刻是否已填充」
 * 成为必须实测的问题。这里做两件事：
 * <ol>
 *   <li>{@link AtlasRuntime#boot} 改成「扫到 0 块不算跑过」⇒ 可以让后续触发点重试；</li>
 *   <li>补一个 {@code ClientTickEvent.Post} 重试闸（tick 20/60/140 各一次，上限 3 次），
 *       每次重试都打印当时的扫描结果 ⇒ 一次启动就能拿到「时间 → 扫到多少块」的证据。</li>
 * </ol>
 * 重试只在扫描为 0 时发生；正常机器上第一条 tick 就直接返回，零开销。
 */
@Mod(value = ChromaAtlasNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class ChromaAtlasNeoForgeClient {

    /** 重试点（客户端 tick 计数）。第一次启动已完成注册表填充，20 tick≈1s 足够。 */
    private static final int[] RETRY_TICKS = {20, 60, 140};

    private boolean booted;
    private int ticks;
    private int retries;

    public ChromaAtlasNeoForgeClient(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        attempt("TagsUpdatedEvent");
    }

    /**
     * 兜底触发：扫描仍为 0 时按 {@link #RETRY_TICKS} 重试（最多 3 次）。
     *
     * <p>为什么用 tick 而不是再挂一个生命周期事件：tick 是唯一「一定会随时间推进」的点，
     * 且能给出「第几 tick 才扫到块」这条时序证据。
     */
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (booted && AtlasRuntime.lastScannedBlocks() > 0) {
            return;                       // 正常路径：第一 tick 就返回
        }
        if (retries >= RETRY_TICKS.length) {
            return;
        }
        ticks++;
        if (ticks != RETRY_TICKS[retries]) {
            return;
        }
        retries++;
        ChromaAtlasNeoForge.LOG.info("[ChromaAtlas] ★M-10 重试★ tick {} 之前扫描仍为 {} 块 ⇒ 第 {}/{} 次重新 boot",
                ticks, AtlasRuntime.lastScannedBlocks(), retries, RETRY_TICKS.length);
        attempt("ClientTick#" + ticks);
    }

    private void attempt(String why) {
        Path dir = Platform.gameDir();
        if (Files.isRegularFile(dir.resolve("config/chromaatlas/probe.on"))) {
            Probe.runOnce("client", ChromaAtlasNeoForge.LOG);
        }
        // ★M-15★ 资源包来源要赶在 boot（启动兜底线程）之前装；本类是 dist=CLIENT 的 @Mod，
        // 走到这里资源一定已 reload 完（TagsUpdatedEvent 在资源/注册表就绪之后）。
        ClientPacks.install();
        if (AtlasRuntime.lastScannedBlocks() > 0) {
            booted = true;                // 已经成功过，别再重扫
            return;
        }
        AtlasRuntime.boot(dir, ChromaAtlasNeoForge.LOG);
        booted = AtlasRuntime.lastScannedBlocks() > 0;
        if (!booted) {
            ChromaAtlasNeoForge.LOG.info("[ChromaAtlas] ★M-10★ {} 触发后仍只扫到 {} 块（遍历 {} / 抛异常 {}）",
                    why, AtlasRuntime.lastScannedBlocks(), AtlasRuntime.lastIterated(), AtlasRuntime.lastCaught());
        }
    }
}
