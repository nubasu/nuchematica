package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.platform.GlStateManager.DestFactor
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import java.util.IdentityHashMap

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

        return if (formatHasColor(renderType.format())) {
            OpacityVertexConsumer(consumer, opacity)
        } else {
            consumer
        }
    }

    internal fun endBatch() {
        try {
            delegate.endBatch()
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        }
    }

    private fun createOpacityRenderType(parent: RenderType): RenderType {
        val hasVertexColor = formatHasColor(parent.format())
        val shouldSortOnUpload = parent.sortOnUpload
        return object : RenderType(
            "nuchematica_be_opacity[$parent]",
            parent.format(),
            parent.mode(),
            parent.bufferSize(),
            parent.affectsCrumbling(),
            shouldSortOnUpload,
            Runnable {
                parent.setupRenderState()
                when (parent) {
                    RenderType.endPortal() -> RenderSystem.setShader(NuchematicaShaders::endPortalOpacity)
                    RenderType.endGateway() -> RenderSystem.setShader(NuchematicaShaders::endGatewayOpacity)
                }

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
                try {
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                } finally {
                    parent.clearRenderState()
                }
            },
        ) {
            override fun end(builder: BufferBuilder, x: Int, y: Int, z: Int): Unit {
                if (!builder.building()) return
                if (shouldSortOnUpload) {
                    builder.setQuadSortOrigin(x.toFloat(), y.toFloat(), z.toFloat())
                }
                builder.end()

                withRenderStateRestored(
                    setup = { setupRenderState() },
                    draw = { BufferUploader.end(builder) },
                    clear = { clearRenderState() },
                )
            }
        }
    }

    /** True when the format carries a COLOR element (the check VertexFormat.hasColor performs). */
    private fun formatHasColor(format: VertexFormat): Boolean =
        format.elements.any { element -> element.usage == VertexFormatElement.Usage.COLOR }
}

internal inline fun withRenderStateRestored(
    setup: () -> Unit,
    draw: () -> Unit,
    clear: () -> Unit,
): Unit {
    try {
        setup()
        draw()
    } finally {
        clear()
    }
}
