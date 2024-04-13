package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.common.SelectedRegion
import com.nubasu.nuchematica.common.Vector3
import net.minecraft.world.phys.Vec3

public object SelectedRegionManager {
    public var selectedRegion: SelectedRegion = SelectedRegion(Vector3.ONE, Vector3.ONE)
    public fun setFirstPosition(position: Vec3): SelectedRegion {
        selectedRegion = SelectedRegion(position.toVector3(), selectedRegion.pos2)
        return selectedRegion
    }

    public fun setSecondPosition(position: Vec3): SelectedRegion {
        selectedRegion = SelectedRegion(selectedRegion.pos1, position.toVector3())
        return selectedRegion
    }

    public fun save() {
    }

    private fun Vec3.toVector3(): Vector3 {
        return Vector3(this.x, this.y, this.z)
    }
}