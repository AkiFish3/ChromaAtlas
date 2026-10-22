package com.akira.chromaatlas.neoforge.mixin;

import com.akira.chromaatlas.neoforge.AppliedColors;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 第二应用点（M9 / M-4）：{@code AbstractBlock#getDefaultMapColor()}（<b>方块级</b>）。
 *
 * <p>为什么需要第二条：FTB Chunks 读的是方块级 {@code defaultMapColor()}
 * （intermediary {@code class_4970;method_26403}），而主 mixin 只拦状态级
 * {@code BlockState#getMapColor}（{@code class_4970$class_4971;method_26205}）⇒ 覆盖不到 FTB。
 * Xaero（Minimap / WorldMap）与 JourneyMap 走状态级，本来就已覆盖（javap 实证）。
 *
 * <p>★为什么 @Mixin 的是 AbstractBlock 而不是 Block★（第 16 轮实测崩溃一次）：
 * {@code method_26403} 的声明类是 {@code class_4970 = AbstractBlock}（yarn `mappings-base.tiny`
 * 第 20930 行，与 `getComparatorOutput` / `canPlaceAt` 同段），不是 {@code Block}。
 * 写在 {@code Block} 上 ⇒ 注解处理器在目标类里找不到该成员 ⇒ <b>refmap 静默不收录</b>，
 * 构建照样绿，进游戏才炸 {@code InvalidInjectionException: could not find any targets}。
 * 排查口诀：refmap 里没有你的方法名 = 构建期就该怀疑，不要等运行时。
 *
 * <p>★口径★ 方块级只能给一个色，取<b>默认状态</b>的颜色 —— 与
 * {@code getDefaultMapColor()} 自身的语义一致。异常一律吞：第三方地图会把一次抛异常
 * 永久记成 bugged 状态并刷日志（R13）。
 */
@Mixin(BlockBehaviour.class)
public abstract class AbstractBlockDefaultMapColorMixin {

    @Inject(method = "defaultMapColor", at = @At("HEAD"), cancellable = true)
    private void chromaatlas$defaultMapColor(CallbackInfoReturnable<MapColor> cir) {
        try {
            // ★AbstractBlock 没有 getDefaultState()★（实测编译报「找不到符号」）⇒ 转成 Block 取
            // 运行时 this 一定是 Block 实例（AbstractBlock 是抽象父类，具体方块都是 Block 子类）
            BlockState st = ((Block) (Object) this).defaultBlockState();
            if (st == null) {
                return;
            }
            MapColor c = AppliedColors.of(st);
            if (c != null) {
                cir.setReturnValue(c);
            }
        } catch (Throwable ignored) {
            // 降级：返回原版值
        }
    }
}
