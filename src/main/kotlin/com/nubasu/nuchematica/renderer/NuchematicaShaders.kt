package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.nubasu.nuchematica.Nuchematica
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.RegisterShadersEvent

internal object NuchematicaShaders {
    private var endPortalOpacity: ShaderInstance? = null
    private var endGatewayOpacity: ShaderInstance? = null

    internal fun registerShaders(event: RegisterShadersEvent) {
        event.registerShader(
            ShaderInstance(
                event.resourceManager,
                ResourceLocation(Nuchematica.MODID, "rendertype_end_portal_opacity"),
                DefaultVertexFormat.POSITION,
            ),
        ) { shader -> endPortalOpacity = shader }
        event.registerShader(
            ShaderInstance(
                event.resourceManager,
                ResourceLocation(Nuchematica.MODID, "rendertype_end_gateway_opacity"),
                DefaultVertexFormat.POSITION,
            ),
        ) { shader -> endGatewayOpacity = shader }
    }

    internal fun endPortalOpacity(): ShaderInstance {
        return checkNotNull(endPortalOpacity) { "end portal opacity shader has not loaded" }
    }

    internal fun endGatewayOpacity(): ShaderInstance {
        return checkNotNull(endGatewayOpacity) { "end gateway opacity shader has not loaded" }
    }
}
