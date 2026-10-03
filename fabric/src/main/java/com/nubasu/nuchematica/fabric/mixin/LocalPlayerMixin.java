package com.nubasu.nuchematica.fabric.mixin;

import com.nubasu.nuchematica.fabric.FabricMixinBridge;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Shadow
    public Input input;

    // The input is adjusted right after it is polled and before anything else in aiStep reads it.
    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(Z)V", shift = At.Shift.AFTER)
    )
    private void nuchematica$afterInputTick(CallbackInfo ci) {
        FabricMixinBridge.onMovementInput((LocalPlayer) (Object) this, this.input);
    }
}
