package com.akira.chromaatlas.fabric.mixin;

import com.akira.chromaatlas.fabric.AppliedColors;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 唯一应用点：{@code AbstractBlock$AbstractBlockState#getMapColor(BlockView, BlockPos)}。
 *
 * <p>截获条件：本 mod 有该方块的覆盖色 ⇒ 直接返回；否则原样放行（= 原版 behavior 不变）。
 *
 * <p>★异常必须吞掉且返回原值★ Xaero 的 {@code MapWriter#hasVanillaColor} 外层是
 * {@code catch (Throwable) { buggedStates.add(state); LOGGER.info(...) }}：
 * 我们一抛，该 BlockState 就被永久标记为 bugged 并刷日志（R13）。
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getMapColor", at = @At("HEAD"), cancellable = true)
    private void chromaatlas$getMapColor(BlockView world, BlockPos pos, CallbackInfoReturnable<MapColor> cir) {
        try {
            MapColor c = AppliedColors.of((BlockState) (Object) this);
            if (c != null) {
                cir.setReturnValue(c);
            }
        } catch (Throwable ignored) {
            // 故意吞：宁可返回原版色，也不能污染第三方地图的状态缓存或刷屏
        }
    }
}
