package com.nubasu.nuchematica.fabric.mixin;

import com.nubasu.nuchematica.fabric.FabricMixinBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow
    public ClientLevel level;

    @Shadow
    public LocalPlayer player;

    // The outgoing level is reported before the field is overwritten, while it is still readable.
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void nuchematica$beforeSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        FabricMixinBridge.onWorldUnload(this.level);
    }

    // The logout is reported once the renderer is reset and before the game mode and the player are cleared,
    // so the player is still readable. The player is null when no session was joined.
    @Inject(
        method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetData()V",
            shift = At.Shift.AFTER
        )
    )
    private void nuchematica$onLoggedOut(Screen screen, CallbackInfo ci) {
        FabricMixinBridge.onLoggedOut(this.player);
    }

    // clearLevel() delegates here. The level is reported after the forced screen tick, which still sees it,
    // and before the field is cleared.
    @Inject(
        method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;updateScreenAndTick(Lnet/minecraft/client/gui/screens/Screen;)V",
            shift = At.Shift.AFTER
        )
    )
    private void nuchematica$beforeClearLevel(Screen screen, CallbackInfo ci) {
        FabricMixinBridge.onWorldUnload(this.level);
    }
}
