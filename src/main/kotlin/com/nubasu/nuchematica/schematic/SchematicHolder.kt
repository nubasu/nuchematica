package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3

public object SchematicHolder {
    public var schematicCache: SchematicCache = SchematicCache(emptyMap(), emptyMap())
    public var schematicSize: Vector3 = Vector3.ZERO
    public var renderingBlocks: SchematicCache = SchematicCache(emptyMap(), emptyMap())
}