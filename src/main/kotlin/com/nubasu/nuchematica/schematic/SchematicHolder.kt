package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import net.minecraft.client.renderer.ShaderInstance

public object SchematicHolder {
    public var schematicCache: SchematicCache = SchematicCache(emptyMap(), emptyMap())
}