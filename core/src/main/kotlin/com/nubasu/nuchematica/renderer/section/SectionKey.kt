package com.nubasu.nuchematica.renderer.section

import net.minecraft.core.BlockPos

internal data class SectionKey(
    internal val x: Int,
    internal val y: Int,
    internal val z: Int,
) {
    internal fun minBlock(): BlockPos {
        return BlockPos(x * SIZE, y * SIZE, z * SIZE)
    }

    internal fun maxExclusiveBlock(): BlockPos {
        return BlockPos((x + 1) * SIZE, (y + 1) * SIZE, (z + 1) * SIZE)
    }

    internal companion object {
        internal const val SIZE: Int = 16

        internal fun of(pos: BlockPos): SectionKey {
            return SectionKey(pos.x shr 4, pos.y shr 4, pos.z shr 4)
        }
    }
}
