package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.platform.GlStateManager.DestFactor
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.fml.util.ObfuscationReflectionHelper
import java.util.IdentityHashMap

/**
 * Isolates block-entity opacity from Minecraft's shared buffers while preserving every requested
 * RenderType. Formats with a color attribute carry opacity in vertex alpha. POSITION-only formats
 * use shader color; the vanilla end portal/gateway shaders get opacity-aware equivalents because
 * they do not declare ColorModulator. Every requested type is wrapped so its opaque state cannot
 * disable blending immediately before the buffered vertices are drawn.
 */
internal class BlockEntityOpacityBufferSource : MultiBufferSource {
    private val delegate = MultiBufferSource.immediate(BufferBuilder(RenderType.BIG_BUFFER_SIZE))
    private val opacityRenderTypes = IdentityHashMap<RenderType, RenderType>()
    private var opacity: Float = 1f

    internal fun begin(opacity: Float) {
        require(opacity > 0f && opacity < 1f) {
            "block-entity opacity buffer is only for partial opacity"
        }
        this.opacity = opacity
    }

    override fun getBuffer(renderType: RenderType): VertexConsumer {
        val opacityRenderType = opacityRenderTypes.getOrPut(renderType) {
            createOpacityRenderType(renderType)
        }
        val consumer = delegate.getBuffer(opacityRenderType)

        return if (renderType.format().hasColor()) {
            OpacityVertexConsumer(consumer, opacity)
        } else {
            consumer
        }
    }

    internal fun endBatch() {
        delegate.endBatch()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun createOpacityRenderType(parent: RenderType): RenderType {
        val hasVertexColor = parent.format().hasColor()
        return object : RenderType(
            "nuchematica_be_opacity[$parent]",
            parent.format(),
            parent.mode(),
            parent.bufferSize(),
            parent.affectsCrumbling(),
            sortOnUpload(parent),
            Runnable {
                parent.setupRenderState()
                when (parent) {
                    RenderType.endPortal() -> RenderSystem.setShader(NuchematicaShaders::endPortalOpacity)
                    RenderType.endGateway() -> RenderSystem.setShader(NuchematicaShaders::endGatewayOpacity)
                }

                // BufferUploader applies the shader again immediately before drawing. Applying it
                // here first synchronizes BlendMode's cache; the second apply then preserves this
                // explicit translucent state instead of restoring the shader's opaque default.
                checkNotNull(RenderSystem.getShader()) {
                    "block-entity RenderType did not select a shader: $parent"
                }.apply()
                RenderSystem.enableBlend()
                RenderSystem.blendFuncSeparate(
                    SourceFactor.SRC_ALPHA,
                    DestFactor.ONE_MINUS_SRC_ALPHA,
                    SourceFactor.ONE,
                    DestFactor.ONE_MINUS_SRC_ALPHA,
                )
                RenderSystem.setShaderColor(1f, 1f, 1f, if (hasVertexColor) 1f else opacity)
            },
            Runnable {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                parent.clearRenderState()
            },
        ) {}
    }

    // RenderType has no accessor for this private flag in 1.18.2. Forge remaps the SRG field name
    // in both development and production, preserving the parent type's upload-time sorting.
    private fun sortOnUpload(parent: RenderType): Boolean =
        checkNotNull(
            ObfuscationReflectionHelper.getPrivateValue(RenderType::class.java, parent, SORT_ON_UPLOAD_FIELD)
        ) { "missing RenderType.sortOnUpload" }

    private companion object {
        private const val SORT_ON_UPLOAD_FIELD = "f_110393_"
    }
}
