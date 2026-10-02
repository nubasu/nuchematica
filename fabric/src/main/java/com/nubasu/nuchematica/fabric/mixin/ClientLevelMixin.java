package com.nubasu.nuchematica.fabric.mixin;

import com.nubasu.nuchematica.fabric.FabricMixinBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    // The notification runs after the constructor body so listeners never see a partially built level.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nuchematica$onConstructed(CallbackInfo ci) {
        FabricMixinBridge.onWorldLoad((ClientLevel) (Object) this);
    }
}
