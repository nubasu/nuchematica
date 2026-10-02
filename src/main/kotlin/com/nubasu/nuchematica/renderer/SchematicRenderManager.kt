package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.DisplayFlag
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.io.SchematicFileLoader
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.renderer.section.RenderTransform
import com.nubasu.nuchematica.renderer.section.SchematicContentSnapshot
import com.nubasu.nuchematica.schematic.ExpectedAirRegion
import com.nubasu.nuchematica.schematic.MissingBlockChange
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.BlockToString.getBlockId
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import java.util.Collections
import kotlin.math.floor

internal enum class SettingsInvalidation {
    NONE,
    DRAW_ONLY,
    TRANSFORM,
    CONTENT,
    LIFECYCLE,
}

internal data class RenderSettingsSnapshot(
    internal val opacity: Float,
    internal val automode: Boolean,
    internal val offsetX: Int,
    internal val offsetY: Int,
    internal val offsetZ: Int,
    internal val rotation: DirectionSetting,
    internal val blockReplaceMap: Map<String, String?>,
    internal val heightLimit: Int,
    internal val visibleBlocks: Set<String>,
    internal val hiddenBlocks: Set<String>,
    internal val displayFlags: DisplayFlag,
    internal val lastLoadedSchematicFile: String,
    internal val initialPositionX: Int,
    internal val initialPositionY: Int,
    internal val initialPositionZ: Int,
    internal val initialRotation: Direction,
) {
    internal companion object {
        internal fun copyOf(settings: RenderSettings): RenderSettingsSnapshot {
            return RenderSettingsSnapshot(
                opacity = settings.opacity,
                automode = settings.automode,
                offsetX = settings.offsetX,
                offsetY = settings.offsetY,
                offsetZ = settings.offsetZ,
                rotation = settings.rotation,
                blockReplaceMap = settings.blockReplaceMap.toMap(),
                heightLimit = settings.heightLimit,
                visibleBlocks = settings.visibleBlocks.toSet(),
                hiddenBlocks = settings.hiddenBlocks.toSet(),
                displayFlags = settings.displayFlags,
                lastLoadedSchematicFile = settings.lastLoadedSchematicFile,
                initialPositionX = settings.initialPosition.x,
                initialPositionY = settings.initialPosition.y,
                initialPositionZ = settings.initialPosition.z,
                initialRotation = settings.initialRotation,
            )
        }
    }
}

internal fun classifySettingsInvalidation(
    previous: RenderSettingsSnapshot?,
    current: RenderSettingsSnapshot,
    forceContent: Boolean = false,
    lifecycleChanged: Boolean = false,
): SettingsInvalidation {
    if (lifecycleChanged) return SettingsInvalidation.LIFECYCLE
    if (forceContent || previous == null) return SettingsInvalidation.CONTENT
    if (
        previous.lastLoadedSchematicFile != current.lastLoadedSchematicFile ||
        previous.hiddenBlocks != current.hiddenBlocks ||
        previous.displayFlags != current.displayFlags ||
        previous.heightLimit != current.heightLimit ||
        previous.automode != current.automode
    ) {
        return SettingsInvalidation.CONTENT
    }
    if (
        previous.offsetX != current.offsetX ||
        previous.offsetY != current.offsetY ||
        previous.offsetZ != current.offsetZ ||
        previous.rotation != current.rotation ||
        previous.initialPositionX != current.initialPositionX ||
        previous.initialPositionY != current.initialPositionY ||
        previous.initialPositionZ != current.initialPositionZ ||
        previous.initialRotation != current.initialRotation
    ) {
        return SettingsInvalidation.TRANSFORM
    }
    if (previous.opacity != current.opacity) return SettingsInvalidation.DRAW_ONLY
    return SettingsInvalidation.NONE
}

internal fun routeSettingsInvalidation(
    previous: RenderSettingsSnapshot?,
    current: RenderSettingsSnapshot,
    forceContent: Boolean = false,
    lifecycleChanged: Boolean = false,
    onDrawOnly: () -> Unit,
    onTransform: () -> Unit,
    onContent: () -> Unit,
    onLifecycle: () -> Unit,
): SettingsInvalidation {
    val invalidation = classifySettingsInvalidation(
        previous = previous,
        current = current,
        forceContent = forceContent,
        lifecycleChanged = lifecycleChanged,
    )
    when (invalidation) {
        SettingsInvalidation.NONE -> Unit
        SettingsInvalidation.DRAW_ONLY -> onDrawOnly()
        SettingsInvalidation.TRANSFORM -> onTransform()
        SettingsInvalidation.CONTENT -> onContent()
        SettingsInvalidation.LIFECYCLE -> onLifecycle()
    }
    return invalidation
}

public object SchematicRenderManager {
    public var isRendering: Boolean = false
    public var initialPosition: Vec3 = Vec3.ZERO
    public var initialDirection: Direction = Direction.NORTH

    // Lazy creation binds renderer checks to the client thread.
    private val schematicRenderer: SectionedSchematicRenderer by lazy { SectionedSchematicRenderer() }
    private val missingBlockRenderer: MissingBlockRender = MissingBlockRender()
    private var offset: Vec3 = Vec3.ZERO
    private var rotate: Float = 0.0f
    private var rotationAxis: Vec3 = Vec3.ZERO
    private var transformRevision: Long = 0L
    private var resourceEpoch: Long = 0L
    private var attachedLevel: ClientLevel? = null
    private var currentRenderSnapshot: SchematicRenderSnapshot? = null
    private var appliedSettings: RenderSettingsSnapshot? = null
    private var settingsApplyPending: Boolean = false
    private var ticksSinceSettingsChange: Int = 0
    private var missingRefreshPending: Boolean = false
    private var missingInitializePending: Boolean = false
    private var pendingMissingInitSettings: RenderSettingsSnapshot? = null

    public fun getRenderBase(): Vec3 {
        return Vec3(
            initialPosition.x + offset.x,
            initialPosition.y + offset.y,
            initialPosition.z + offset.z,
        )
    }

    public fun setOffset(vec3: Vec3): Unit {
        offset = vec3
        transformRevision++
    }

    public fun setRotation(rot: Float, axis: Vec3): Unit {
        rotate = rot
        rotationAxis = axis
        transformRevision++
    }

    public fun setRotation(rotation: DirectionSetting): Unit {
        setRotation(rotationDegrees(rotation), rotationAxis(initialDirection, rotation))
    }

    internal fun worldBlockToLocal(pos: BlockPos): BlockPos {
        return currentTransform().worldBlockToLocal(pos)
    }

    internal fun localBlockToWorld(pos: BlockPos): BlockPos {
        return currentTransform().localBlockToWorld(pos)
    }

    internal fun currentTransformRevision(): Long {
        return transformRevision
    }

    public fun initialize(): Unit {
        settingsApplyPending = false
        resetTransformToPlayer()
        syncCurrentTransformToSettings(RenderSettingHolder.renderSettings)
        isRendering = true
        applySettingsInternal(
            settings = RenderSettingHolder.renderSettings,
            forceContent = true,
            contentAlreadyLoaded = true,
        )
    }

    public fun updateInitialPosition(): Unit {
        resetTransformToPlayer()
        syncCurrentTransformToSettings(RenderSettingHolder.renderSettings)
        applySettingsInternal(RenderSettingHolder.renderSettings)
    }

    public fun updateInitialPosition(direction: Direction, position: Vec3): Unit {
        val settings = RenderSettingHolder.renderSettings
        settings.initialRotation = direction
        settings.initialPosition = Vector3(position.x, position.y, position.z)
        applySettingsInternal(settings)
    }

    public fun applySettings(settings: RenderSettings): Boolean {
        settingsApplyPending = false
        return applySettingsInternal(settings)
    }

    internal fun scheduleSettingsApply(): Unit {
        settingsApplyPending = true
        ticksSinceSettingsChange = 0
    }

    internal fun tickPendingSettings(): Unit {
        refreshMissingBlocksAfterWorldLoad()
        pumpPrintWorldModelCapture()
        if (!MissingBlockHolder.isInitializing()) {
            for (change in MissingBlockHolder.pumpUnknownChunks()) {
                onMissingBlockChange(change)
            }
        }
        if (!settingsApplyPending) return
        if (++ticksSinceSettingsChange >= SETTINGS_APPLY_DELAY_TICKS) {
            flushPendingSettings()
        }
    }

    internal fun flushPendingSettings(): Unit {
        if (!settingsApplyPending) return
        settingsApplyPending = false
        applySettingsInternal(RenderSettingHolder.renderSettings)
    }

    internal fun worldLoaded(level: ClientLevel): Unit {
        routeLifecycleInvalidation {
            val previousLevel = attachedLevel
            if (previousLevel !== null && previousLevel !== level) {
                schematicRenderer.detachLevel(previousLevel)
            }
            attachedLevel = level
            currentRenderSnapshot = null
            schematicRenderer.attachLevel(level)
            if (isRendering || SchematicHolder.renderingBlocks.blocks.isNotEmpty()) {
                replaceCurrentContent(level)
            }
            missingRefreshPending = isRendering
        }
    }

    internal fun worldUnloaded(level: ClientLevel): Unit {
        if (attachedLevel !== level) return
        routeLifecycleInvalidation {
            schematicRenderer.detachLevel(level)
            attachedLevel = null
            currentRenderSnapshot = null
            missingRefreshPending = false
            missingInitializePending = false
            pendingMissingInitSettings = null
            PrintWorldModel.cancel()
        }
    }

    internal fun resourceReloaded(): Unit {
        routeLifecycleInvalidation {
            resourceEpoch++
            schematicRenderer.clear()
            currentRenderSnapshot = null
            val level = attachedLevel ?: Minecraft.getInstance().level
            if (level != null && isRendering) {
                if (attachedLevel == null) {
                    attachedLevel = level
                    schematicRenderer.attachLevel(level)
                }
                replaceCurrentContent(level)
            }
        }
    }

    public fun rerender(): Unit {
        applySettingsInternal(
            settings = RenderSettingHolder.renderSettings,
            forceContent = true,
            contentAlreadyLoaded = true,
        )
    }

    public fun render(event: RenderLevelStageEvent): Unit {
        if (!isRendering) return
        val level = ensureAttachedLevel() ?: return
        if (currentRenderSnapshot == null) {
            replaceCurrentContent(level)
        }
        schematicRenderer.render(event, RenderSettingHolder.renderSettings.opacity)
        missingBlockRenderer.render(currentTransform(), event)
    }

    public fun updatePlacedBlocks(): Unit {
        missingBlockRenderer.initialize()
    }

    internal fun onMissingBlockChange(change: MissingBlockChange): Unit {
        if (change.overlayChanged || change.extraChanged) {
            missingBlockRenderer.markChanged(change.localPos)
        }
        if (appliedSettings?.automode == true && change.satisfiedChanged) {
            schematicRenderer.setBlockSuppressed(change.localPos, change.satisfied)
        }
    }

    public fun loadRenderBlocks(schematicFile: String): Boolean {
        return SchematicFileLoader.loadRenderBlocks(schematicFile)
    }

    public fun applyFilterBlock(): Unit {
        applyFilterBlock(RenderSettingsSnapshot.copyOf(RenderSettingHolder.renderSettings))
    }

    private fun applySettingsInternal(
        settings: RenderSettings,
        forceContent: Boolean = false,
        contentAlreadyLoaded: Boolean = false,
    ): Boolean {
        val next = RenderSettingsSnapshot.copyOf(settings)
        val invalidation = classifySettingsInvalidation(appliedSettings, next, forceContent)
        val fileChanged = appliedSettings?.lastLoadedSchematicFile != next.lastLoadedSchematicFile
        if (
            invalidation == SettingsInvalidation.CONTENT &&
            fileChanged &&
            !contentAlreadyLoaded
        ) {
            if (next.lastLoadedSchematicFile.isEmpty()) return false
            if (!SchematicFileLoader.loadRenderBlocks(next.lastLoadedSchematicFile)) return false
        }

        routeSettingsInvalidation(
            previous = appliedSettings,
            current = next,
            forceContent = forceContent,
            onDrawOnly = {},
            onTransform = { applyTransformInvalidation(next) },
            onContent = { applyContentInvalidation(next) },
            onLifecycle = { error("settings application cannot emit lifecycle invalidation") },
        )
        appliedSettings = next
        return true
    }

    private fun applyTransformInvalidation(settings: RenderSettingsSnapshot): Unit {
        applyTransform(settings)
        val level = ensureAttachedLevel() ?: return
        val current = currentRenderSnapshot
        if (current == null) {
            replaceCurrentContent(level)
        } else {
            val updated = current.copy(transform = currentTransform())
            currentRenderSnapshot = updated
            schematicRenderer.updateTransform(updated)
        }
        if (Minecraft.getInstance().level != null) {
            if (initMissingBlock(settings)) {
                syncSatisfiedPositions(settings)
            }
        }
    }

    private fun applyContentInvalidation(settings: RenderSettingsSnapshot): Unit {
        applyTransform(settings)
        applyFilterBlock(settings)
        val level = ensureAttachedLevel()
        val suppressedPositions = if (Minecraft.getInstance().level == null) {
            emptySet()
        } else if (initMissingBlock(settings)) {
            initialSuppressedPositions(settings)
        } else {
            emptySet()
        }
        if (level != null) {
            replaceCurrentContent(level, suppressedPositions)
        }
    }

    private fun applyTransform(settings: RenderSettingsSnapshot): Unit {
        initialPosition = Vec3(
            settings.initialPositionX.toDouble(),
            settings.initialPositionY.toDouble(),
            settings.initialPositionZ.toDouble(),
        )
        initialDirection = settings.initialRotation
        offset = Vec3(
            settings.offsetX.toDouble(),
            settings.offsetY.toDouble(),
            settings.offsetZ.toDouble(),
        )
        rotate = rotationDegrees(settings.rotation)
        rotationAxis = rotationAxis(initialDirection, settings.rotation)
        transformRevision++
    }

    private fun replaceCurrentContent(
        level: ClientLevel,
        suppressedPositions: Set<BlockPos> = emptySet(),
    ): Unit {
        val cache = SchematicHolder.renderingBlocks
        val content = SchematicContentSnapshot.copyOf(cache.blocks, Blocks.AIR.defaultBlockState())
        val blockEntities = Collections.unmodifiableMap(LinkedHashMap(cache.blockEntities))
        val next = SchematicRenderSnapshot(
            level = level,
            content = content,
            blockEntities = blockEntities,
            suppressedPositions = suppressedPositions.toSet(),
            transform = currentTransform(),
            resourceEpoch = resourceEpoch,
        )
        currentRenderSnapshot = next
        schematicRenderer.replaceContent(next)
    }

    private fun ensureAttachedLevel(): ClientLevel? {
        val current = attachedLevel
        if (current != null) return current
        val level = Minecraft.getInstance().level ?: return null
        attachedLevel = level
        schematicRenderer.attachLevel(level)
        return level
    }

    private fun currentTransform(): RenderTransform {
        return RenderTransform(
            renderBase = getRenderBase(),
            rotateDeg = rotate,
            rotateAxis = rotationAxis,
            revision = transformRevision,
        )
    }

    private fun resetTransformToPlayer(): Unit {
        val player = Minecraft.getInstance().player ?: return
        val playerPos = player.position()
        val size = SchematicHolder.schematicSize
        rotationAxis = Vec3.ZERO
        offset = Vec3.ZERO
        rotate = 0.0f
        initialDirection = player.direction
        initialPosition = when (initialDirection) {
            Direction.EAST -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
            Direction.SOUTH -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z))
            Direction.WEST -> Vec3(
                floor(playerPos.x - size.x),
                floor(playerPos.y),
                floor(playerPos.z - size.z),
            )
            Direction.NORTH -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z - size.z))
            else -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
        }
        transformRevision++
    }

    private fun syncCurrentTransformToSettings(settings: RenderSettings): Unit {
        settings.offsetX = offset.x.toInt()
        settings.offsetY = offset.y.toInt()
        settings.offsetZ = offset.z.toInt()
        settings.rotation = directionSetting(rotate)
        settings.initialPosition = Vector3(initialPosition.x, initialPosition.y, initialPosition.z)
        settings.initialRotation = initialDirection
    }

    private fun refreshMissingBlocksAfterWorldLoad(): Unit {
        if (!missingRefreshPending) return
        val level = attachedLevel ?: return
        if (Minecraft.getInstance().level !== level) return
        missingRefreshPending = false
        val settings = appliedSettings ?: return
        if (initMissingBlock(settings)) {
            syncSatisfiedPositions(settings)
        }
    }

    /**
     * Starts or discards the print-world capture when the printer toggles.
     *
     * The capture only serves the printer and mover; the missing-block overlay reads
     * the live world directly while the printer is off.
     */
    internal fun printerActivationChanged(enabled: Boolean): Unit {
        if (!enabled) {
            PrintWorldModel.cancel()
            return
        }
        if (Minecraft.getInstance().level == null) return
        val settings = appliedSettings ?: return
        if (initMissingBlock(settings)) {
            syncSatisfiedPositions(settings)
        }
    }

    private fun initMissingBlock(settings: RenderSettingsSnapshot): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        if (SchematicPrinter.enabled) {
            val content = SchematicHolder.renderingBlocks
            var status = PrintWorldModel.ensureCapture(
                level = level,
                contentIdentity = content,
                transformRevision = transformRevision,
                localPositions = content.blocks.keys,
                localToWorld = ::localBlockToWorld,
            )
            if (status == PrintWorldModel.Status.CAPTURING) {
                status = PrintWorldModel.pump(level)
            }
            if (status == PrintWorldModel.Status.CAPTURING) {
                missingInitializePending = true
                pendingMissingInitSettings = settings
                return false
            }
        }
        MissingBlockHolder.initialize()
        if (MissingBlockHolder.isInitializing()) {
            missingInitializePending = true
            pendingMissingInitSettings = settings
            return false
        }
        missingInitializePending = false
        pendingMissingInitSettings = null
        missingBlockRenderer.initialize()
        return true
    }

    private fun pumpPrintWorldModelCapture(): Unit {
        if (!missingInitializePending) return
        val level = attachedLevel ?: Minecraft.getInstance().level ?: return
        if (PrintWorldModel.pump(level) == PrintWorldModel.Status.CAPTURING) return
        if (MissingBlockHolder.isInitializing()) {
            MissingBlockHolder.pump()
        } else {
            MissingBlockHolder.initialize()
        }
        if (MissingBlockHolder.isInitializing()) return
        missingInitializePending = false
        missingBlockRenderer.initialize()
        val settings = pendingMissingInitSettings
        pendingMissingInitSettings = null
        if (settings?.automode == true) {
            for (pos in MissingBlockHolder.satisfiedPositions()) {
                schematicRenderer.setBlockSuppressed(pos, true)
            }
        }
    }

    private fun initialSuppressedPositions(settings: RenderSettingsSnapshot): Set<BlockPos> {
        return if (settings.automode) MissingBlockHolder.satisfiedPositions() else emptySet()
    }

    private fun syncSatisfiedPositions(settings: RenderSettingsSnapshot): Unit {
        if (!settings.automode) return
        for (pos in MissingBlockHolder.satisfiedPositions()) {
            schematicRenderer.setBlockSuppressed(pos, true)
        }
    }

    private fun applyFilterBlock(settings: RenderSettingsSnapshot): Unit {
        fun isVisible(pos: BlockPos, block: Block): Boolean {
            if (settings.hiddenBlocks.contains(getBlockId(block))) return false
            return when (settings.displayFlags) {
                DisplayFlag.ALL -> true
                DisplayFlag.UP_TO_HEIGHT -> pos.y <= settings.heightLimit
                DisplayFlag.ONLY_HEIGHT -> pos.y == settings.heightLimit
            }
        }

        val filteredBlocks = SchematicHolder.schematicCache.blocks.filter { (pos, state) ->
            isVisible(pos, state.block)
        }
        val filteredEntities = SchematicHolder.schematicCache.blockEntities.filter { (pos, entity) ->
            isVisible(pos, entity.blockState.block)
        }
        SchematicHolder.renderingBlocks = SchematicCache(filteredBlocks, filteredEntities)
        val (expectedAirYMin, expectedAirYMax) = when (settings.displayFlags) {
            DisplayFlag.ALL -> Int.MIN_VALUE to Int.MAX_VALUE
            DisplayFlag.UP_TO_HEIGHT -> Int.MIN_VALUE to settings.heightLimit
            DisplayFlag.ONLY_HEIGHT -> settings.heightLimit to settings.heightLimit
        }
        SchematicHolder.expectedAirRegion = ExpectedAirRegion.of(
            SchematicHolder.schematicCache.blocks.keys,
            expectedAirYMin,
            expectedAirYMax,
        )
    }

    private fun routeLifecycleInvalidation(action: () -> Unit): Unit {
        val current = RenderSettingsSnapshot.copyOf(RenderSettingHolder.renderSettings)
        routeSettingsInvalidation(
            previous = appliedSettings,
            current = current,
            lifecycleChanged = true,
            onDrawOnly = { error("lifecycle routing selected draw-only invalidation") },
            onTransform = { error("lifecycle routing selected transform invalidation") },
            onContent = { error("lifecycle routing selected content invalidation") },
            onLifecycle = action,
        )
    }

    private fun rotationDegrees(rotation: DirectionSetting): Float {
        return when (rotation) {
            DirectionSetting.CLOCKWISE_0 -> 0.0f
            DirectionSetting.CLOCKWISE_90 -> 90.0f
            DirectionSetting.CLOCKWISE_180 -> 180.0f
            DirectionSetting.CLOCKWISE_270 -> 270.0f
        }
    }

    private fun directionSetting(degrees: Float): DirectionSetting {
        return when (degrees.toInt()) {
            0 -> DirectionSetting.CLOCKWISE_0
            90 -> DirectionSetting.CLOCKWISE_90
            180 -> DirectionSetting.CLOCKWISE_180
            270 -> DirectionSetting.CLOCKWISE_270
            else -> error("unsupported schematic rotation: $degrees")
        }
    }

    private fun rotationAxis(direction: Direction, rotation: DirectionSetting): Vec3 {
        val sizeX = SchematicHolder.schematicSize.x + 1.0
        val sizeZ = SchematicHolder.schematicSize.z + 1.0
        return when (direction) {
            Direction.EAST -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeX, 0.0, 0.0)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -sizeZ)
            }
            Direction.SOUTH -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeX, 0.0, sizeX - sizeZ)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -sizeZ - (sizeX - sizeZ))
            }
            Direction.WEST -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeZ, 0.0, sizeX - sizeZ)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(sizeZ - sizeX, 0.0, -sizeZ - (sizeX - sizeZ))
            }
            Direction.NORTH -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeZ, 0.0, 0.0)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(sizeZ - sizeX, 0.0, -sizeZ)
            }
            else -> rotationAxis(Direction.EAST, rotation)
        }
    }

    private const val SETTINGS_APPLY_DELAY_TICKS: Int = 4
}
