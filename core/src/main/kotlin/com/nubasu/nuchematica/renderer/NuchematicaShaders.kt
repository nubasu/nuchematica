package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.Nuchematica
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation

internal object NuchematicaShaders {
    private var endPortalOpacity: ShaderInstance? = null
    private var endGatewayOpacity: ShaderInstance? = null

    internal val END_PORTAL_OPACITY: ResourceLocation =
        ResourceLocation(Nuchematica.MODID, "rendertype_end_portal_opacity")

    internal val END_GATEWAY_OPACITY: ResourceLocation =
        ResourceLocation(Nuchematica.MODID, "rendertype_end_gateway_opacity")

    internal fun endPortalOpacityLoaded(shader: ShaderInstance) {
        endPortalOpacity = shader
    }

    internal fun endGatewayOpacityLoaded(shader: ShaderInstance) {
        endGatewayOpacity = shader
    }

    internal fun endPortalOpacity(): ShaderInstance {
        return checkNotNull(endPortalOpacity) { "end portal opacity shader has not loaded" }
    }

    internal fun endGatewayOpacity(): ShaderInstance {
        return checkNotNull(endGatewayOpacity) { "end gateway opacity shader has not loaded" }
    }
}
