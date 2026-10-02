package com.nubasu.nuchematica.renderer

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class NuchematicaShaderResourceTest {

    @Test
    public fun portalAndGatewayShadersExposeOpacityUniform(): Unit {
        assertPortalShader("rendertype_end_portal_opacity.json", 15)
        assertPortalShader("rendertype_end_gateway_opacity.json", 16)
    }

    private fun assertPortalShader(resourceName: String, layers: Int) {
        val path = "/assets/nuchematica/shaders/core/$resourceName"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }
        val json = stream.reader().use { JsonParser.parseReader(it).asJsonObject }

        assertEquals("minecraft:rendertype_end_portal", json["vertex"].asString)
        assertEquals("nuchematica:rendertype_end_portal_opacity", json["fragment"].asString)

        val uniforms = json.getAsJsonArray("uniforms")
        assertTrue(uniforms.any { it.asJsonObject["name"].asString == "ColorModulator" })
        val layerUniform = uniforms.first { it.asJsonObject["name"].asString == "EndPortalLayers" }
        assertEquals(layers, layerUniform.asJsonObject.getAsJsonArray("values")[0].asInt)
    }
}
