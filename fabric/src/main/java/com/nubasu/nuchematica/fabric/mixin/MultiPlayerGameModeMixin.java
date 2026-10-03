package com.nubasu.nuchematica.fabric.mixin;

import com.nubasu.nuchematica.fabric.FabricMixinBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    // The survival branch is the second tutorial notification in the method; the first belongs to the
    // creative branch, which the attack-block event already covers.
    @Inject(
        method = "continueDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/tutorial/Tutorial;onDestroyBlock(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void nuchematica$afterSurvivalProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        FabricMixinBridge.onLeftClickBlock(this.minecraft.level, pos);
    }
}
