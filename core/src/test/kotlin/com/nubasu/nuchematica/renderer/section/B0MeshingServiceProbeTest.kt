package com.nubasu.nuchematica.renderer.section

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class B0MeshingServiceProbeTest {

    @Test
    public fun handWrittenFakeSuppliesOnlyLayersBlockQuadsAndFluidQuads(): Unit {
        val service = FakeMeshingService()
        val routed = route(
            service,
            listOf(
                ProbeCell("stone", hasFluid = false),
                ProbeCell("waterlogged_glass", hasFluid = true),
            ),
        )

        assertEquals(listOf("stone-solid", "glass-translucent"), routed.blockQuads)
        assertEquals(listOf("water-translucent"), routed.fluidQuads)
        assertEquals(2, service.layerQueries)
        assertEquals(2, service.blockQueries)
        assertEquals(1, service.fluidQueries)
        println("B0_FAKE_SEAM PASS layers=2 blockQuads=2 fluidQuads=1")
    }

    private fun route(service: ProbeMeshingService, cells: List<ProbeCell>): RoutedQuads {
        val blockQuads = mutableListOf<String>()
        val fluidQuads = mutableListOf<String>()
        for (cell in cells) {
            for (layer in service.layersFor(cell.id)) {
                blockQuads += service.blockQuads(cell.id, layer)
                if (cell.hasFluid) {
                    fluidQuads += service.fluidQuads(cell.id, layer)
                }
            }
        }
        return RoutedQuads(blockQuads, fluidQuads)
    }

    private interface ProbeMeshingService {
        fun layersFor(cellId: String): Set<ProbeLayer>

        fun blockQuads(cellId: String, layer: ProbeLayer): List<String>

        fun fluidQuads(cellId: String, layer: ProbeLayer): List<String>
    }

    private class FakeMeshingService : ProbeMeshingService {
        var layerQueries: Int = 0
        var blockQueries: Int = 0
        var fluidQueries: Int = 0

        override fun layersFor(cellId: String): Set<ProbeLayer> {
            layerQueries++
            return when (cellId) {
                "stone" -> setOf(ProbeLayer.SOLID)
                "waterlogged_glass" -> setOf(ProbeLayer.TRANSLUCENT)
                else -> emptySet()
            }
        }

        override fun blockQuads(cellId: String, layer: ProbeLayer): List<String> {
            blockQueries++
            return when (cellId to layer) {
                "stone" to ProbeLayer.SOLID -> listOf("stone-solid")
                "waterlogged_glass" to ProbeLayer.TRANSLUCENT -> listOf("glass-translucent")
                else -> emptyList()
            }
        }

        override fun fluidQuads(cellId: String, layer: ProbeLayer): List<String> {
            fluidQueries++
            return if (cellId == "waterlogged_glass" && layer == ProbeLayer.TRANSLUCENT) {
                listOf("water-translucent")
            } else {
                emptyList()
            }
        }
    }

    private enum class ProbeLayer {
        SOLID,
        TRANSLUCENT,
    }

    private data class ProbeCell(val id: String, val hasFluid: Boolean)

    private data class RoutedQuads(
        val blockQuads: List<String>,
        val fluidQuads: List<String>,
    )
}
