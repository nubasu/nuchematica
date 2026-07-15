package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.DisplayFlag
import com.nubasu.nuchematica.gui.RenderSettings
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class SchematicRendererIntegrationTest {
    @Test
    public fun settingsDiffRoutesOnlyTheStrongestInvalidationOnce(): Unit {
        val base = RenderSettings()
        val previous = RenderSettingsSnapshot.copyOf(base)
        val current = RenderSettings(
            opacity = 0.75f,
            offsetX = 3,
            hiddenBlocks = mutableSetOf("minecraft:stone"),
        )
        var drawCalls = 0
        var transformCalls = 0
        var contentCalls = 0
        var lifecycleCalls = 0

        val result = routeSettingsInvalidation(
            previous = previous,
            current = RenderSettingsSnapshot.copyOf(current),
            onDrawOnly = { drawCalls++ },
            onTransform = { transformCalls++ },
            onContent = { contentCalls++ },
            onLifecycle = { lifecycleCalls++ },
        )

        assertEquals(SettingsInvalidation.CONTENT, result)
        assertEquals(0, drawCalls)
        assertEquals(0, transformCalls)
        assertEquals(1, contentCalls)
        assertEquals(0, lifecycleCalls)
    }

    @Test
    public fun twentyOpacityChangesScheduleNoGeometryOrSortWork(): Unit {
        val settings = RenderSettings()
        var previous = RenderSettingsSnapshot.copyOf(settings)
        var drawCalls = 0
        var geometryCalls = 0

        repeat(20) { index ->
            settings.opacity = (index + 1) / 21.0f
            val current = RenderSettingsSnapshot.copyOf(settings)
            assertEquals(
                SettingsInvalidation.DRAW_ONLY,
                routeSettingsInvalidation(
                    previous = previous,
                    current = current,
                    onDrawOnly = { drawCalls++ },
                    onTransform = { geometryCalls++ },
                    onContent = { geometryCalls++ },
                    onLifecycle = { geometryCalls++ },
                ),
            )
            previous = current
        }

        assertEquals(20, drawCalls)
        assertEquals(0, geometryCalls)
    }

    @Test
    public fun settingsSnapshotCopiesMutableCollectionsAndIgnoresUnconnectedFields(): Unit {
        val settings = RenderSettings(
            visibleBlocks = mutableSetOf("minecraft:stone"),
            hiddenBlocks = mutableSetOf("minecraft:dirt"),
            blockReplaceMap = mutableMapOf("minecraft:stone" to "minecraft:glass"),
        )
        val snapshot = RenderSettingsSnapshot.copyOf(settings)

        settings.visibleBlocks += "minecraft:glass"
        settings.hiddenBlocks += "minecraft:glass"
        settings.blockReplaceMap["minecraft:dirt"] = null

        assertEquals(setOf("minecraft:stone"), snapshot.visibleBlocks)
        assertEquals(setOf("minecraft:dirt"), snapshot.hiddenBlocks)
        assertEquals(mapOf("minecraft:stone" to "minecraft:glass"), snapshot.blockReplaceMap)

        val onlyUnconnectedFieldsChanged = RenderSettingsSnapshot.copyOf(
            RenderSettings(
                visibleBlocks = mutableSetOf("minecraft:glass"),
                hiddenBlocks = mutableSetOf("minecraft:dirt"),
                blockReplaceMap = mutableMapOf("minecraft:dirt" to null),
            ),
        )
        assertEquals(
            SettingsInvalidation.NONE,
            classifySettingsInvalidation(snapshot, onlyUnconnectedFieldsChanged),
        )
    }

    @Test
    public fun contractFieldsMapToContentTransformAndDrawOnly(): Unit {
        val base = RenderSettingsSnapshot.copyOf(RenderSettings())
        val contentChanges = listOf(
            RenderSettings(lastLoadedSchematicFile = "other.schem"),
            RenderSettings(hiddenBlocks = mutableSetOf("minecraft:stone")),
            RenderSettings(displayFlags = DisplayFlag.ONLY_HEIGHT),
            RenderSettings(heightLimit = 4),
            RenderSettings(automode = true),
        )
        val transformChanges = listOf(
            RenderSettings(offsetX = 1),
            RenderSettings(offsetY = 1),
            RenderSettings(offsetZ = 1),
            RenderSettings(rotation = DirectionSetting.CLOCKWISE_90),
            RenderSettings(initialPosition = Vector3(1, 2, 3)),
            RenderSettings(initialRotation = Direction.SOUTH),
        )

        for (settings in contentChanges) {
            assertEquals(
                SettingsInvalidation.CONTENT,
                classifySettingsInvalidation(base, RenderSettingsSnapshot.copyOf(settings)),
            )
        }
        for (settings in transformChanges) {
            assertEquals(
                SettingsInvalidation.TRANSFORM,
                classifySettingsInvalidation(base, RenderSettingsSnapshot.copyOf(settings)),
            )
        }
        assertEquals(
            SettingsInvalidation.DRAW_ONLY,
            classifySettingsInvalidation(base, RenderSettingsSnapshot.copyOf(RenderSettings(opacity = 1.0f))),
        )
        assertEquals(
            SettingsInvalidation.LIFECYCLE,
            classifySettingsInvalidation(base, base, lifecycleChanged = true),
        )
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
