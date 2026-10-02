package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.world.phys.Vec3

public object SchematicEditor {
    public fun applyJson(settings: RenderSettings): Unit {
        if (settings.lastLoadedSchematicFile.isEmpty()) return
        SchematicRenderManager.applySettings(settings)
    }

    public fun translate(offset: Vector3): Unit {
        SchematicRenderManager.setOffset(
            Vec3(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()),
        )
    }

    public fun rotate(rotation: DirectionSetting): Unit {
        SchematicRenderManager.setRotation(rotation)
    }
}
