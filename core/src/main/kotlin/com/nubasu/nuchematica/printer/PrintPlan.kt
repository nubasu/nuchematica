package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Atomic plan operations.
 *
 * Actions sharing a group ID form one ordered target/scaffold transaction.
 * Scaffolded groups place their chain, place one target, then remove the chain in reverse.
 * Dependencies on place actions refer to planned producer positions for reservations.
 */
internal sealed interface PlanAction {
    data class PlaceTarget(
        internal val pos: BlockPos,
        internal val expected: BlockState,
        internal val dependsOn: List<BlockPos> = emptyList(),
        internal val groupId: Int = 0,
        internal val stance: PlannedStance? = null,
    ) : PlanAction
    data class PlaceScaffold(
        internal val pos: BlockPos,
        internal val dependsOn: List<BlockPos> = emptyList(),
        internal val groupId: Int = 0,
    ) : PlanAction
    data class RemoveScaffold(internal val pos: BlockPos, internal val groupId: Int = 0) : PlanAction
}

internal data class PlannedStance(
    internal val feet: BlockPos,
    internal val hitFace: Direction?,
    internal val hit: Vec3?,
)

/** Natural actions for one band/tile unit; reservations are stored by [PrintPlan]. */
internal data class PlanUnit(
    internal val band: Int,
    internal val tileX: Int,
    internal val tileZ: Int,
    internal val actions: List<PlanAction>,
)

internal enum class ExclusionReason { CATEGORY, OCCUPIED, FALLING }

internal data class ExcludedPosition(internal val pos: BlockPos, internal val reason: ExclusionReason)

internal data class BuildabilityReport(
    internal val directCount: Int,
    internal val scaffoldCount: Int,
    internal val reservedCount: Int,
    internal val excludedCategoryCount: Int,
    internal val excludedOccupiedCount: Int,
    internal val excludedFallingCount: Int,
    internal val unreachableCount: Int,
    internal val alreadyPlacedCount: Int,
    internal val excludedPositions: List<ExcludedPosition>,
    internal val unreachablePositions: List<BlockPos>,
    internal val scaffoldMaterialPositions: List<BlockPos>,
)

internal enum class ReservationOrigin { SUPPORT, SURVIVAL }

internal enum class ReservationMethod { DIRECT, SCAFFOLD }

internal data class ReservationDetail(
    internal val pos: BlockPos,
    internal val originOrder: Int,
    internal val origin: ReservationOrigin,
    internal val effectiveMethod: ReservationMethod,
    internal val triggerUnit: Int,
    internal val dependsOn: List<BlockPos>,
)

/**
 * Complete classification with natural units and support-triggered reservations.
 *
 * Reservations are bucketed by trigger unit; [executablePlanUnits] refines their insertion
 * using producer dependencies.
 */
internal data class PrintPlan(
    internal val units: List<PlanUnit>,
    internal val reservations: Map<Int, List<PlanAction>>,
    internal val report: BuildabilityReport,
    internal val reservationDetails: List<ReservationDetail> = emptyList(),
)

internal data class ExecutablePlanUnit(
    internal val band: Int,
    internal val tileX: Int,
    internal val tileZ: Int,
    internal val actions: List<PlanAction>,
    internal val isReservation: Boolean,
)

private data class ExecutableActionGroup(
    internal val groupId: Int,
    internal val actions: List<PlanAction>,
    internal val placedTargets: Set<BlockPos>,
    internal val dependencies: Set<BlockPos>,
)

private fun actionDependencies(action: PlanAction): List<BlockPos> {
    return when (action) {
        is PlanAction.PlaceTarget -> action.dependsOn
        is PlanAction.PlaceScaffold -> action.dependsOn
        is PlanAction.RemoveScaffold -> emptyList()
    }
}

/** Splits actions into atomic contiguous groups. */
private fun contiguousActionGroups(actions: List<PlanAction>, source: String): List<ExecutableActionGroup> {
    val groups = mutableListOf<ExecutableActionGroup>()
    val seenGroups = mutableSetOf<Int>()
    var start = 0
    while (start < actions.size) {
        val groupId = groupIdOf(actions[start])
        require(groupId !in seenGroups) { "$source group $groupId is not contiguous" }
        seenGroups += groupId
        var end = start + 1
        while (end < actions.size && groupIdOf(actions[end]) == groupId) end++
        val groupActions = actions.subList(start, end)
        groups += ExecutableActionGroup(
            groupId = groupId,
            actions = groupActions,
            placedTargets = groupActions.filterIsInstance<PlanAction.PlaceTarget>().mapTo(LinkedHashSet()) { it.pos },
            dependencies = groupActions.flatMapTo(LinkedHashSet(), ::actionDependencies),
        )
        start = end
    }
    return groups
}

/**
 * Inserts reserved groups after their local producer dependencies.
 *
 * Group order stays deterministic and every group remains contiguous.
 */
internal fun executablePlanUnits(plan: PrintPlan): List<ExecutablePlanUnit> {
    for (triggerUnit in plan.reservations.keys) {
        require(triggerUnit in plan.units.indices) {
            "reservation trigger unit $triggerUnit is outside plan units ${plan.units.indices}"
        }
    }

    val executable = mutableListOf<ExecutablePlanUnit>()
    for ((triggerUnitIndex, naturalUnit) in plan.units.withIndex()) {
        val reservationActions = plan.reservations[triggerUnitIndex].orEmpty()
        if (reservationActions.isEmpty()) {
            executable += ExecutablePlanUnit(
                band = naturalUnit.band,
                tileX = naturalUnit.tileX,
                tileZ = naturalUnit.tileZ,
                actions = naturalUnit.actions,
                isReservation = false,
            )
            continue
        }

        val naturalGroups = contiguousActionGroups(naturalUnit.actions, "natural unit $triggerUnitIndex")
        val pendingReservations = contiguousActionGroups(
            reservationActions,
            "reservation trigger unit $triggerUnitIndex",
        ).toMutableList()
        val localProducerPositions = HashSet<BlockPos>()
        for (group in naturalGroups) localProducerPositions += group.placedTargets
        for (group in pendingReservations) localProducerPositions += group.placedTargets
        val availableLocalProducers = HashSet<BlockPos>()
        val naturalChunk = mutableListOf<PlanAction>()

        fun appendUnit(actions: List<PlanAction>, isReservation: Boolean): Unit {
            executable += ExecutablePlanUnit(
                band = naturalUnit.band,
                tileX = naturalUnit.tileX,
                tileZ = naturalUnit.tileZ,
                actions = actions,
                isReservation = isReservation,
            )
        }

        fun flushNaturalChunk(): Unit {
            if (naturalChunk.isEmpty()) return
            appendUnit(naturalChunk.toList(), isReservation = false)
            naturalChunk.clear()
        }

        fun drainReadyReservations(allowUnitEndFallback: Boolean): Unit {
            while (true) {
                val readyIndex = pendingReservations.indexOfFirst { group ->
                    val localDependencies = group.dependencies.filterTo(HashSet()) { it in localProducerPositions }
                    localDependencies.all { it in availableLocalProducers } &&
                        (localDependencies.isNotEmpty() || allowUnitEndFallback)
                }
                if (readyIndex < 0) return
                flushNaturalChunk()
                val ready = pendingReservations.removeAt(readyIndex)
                appendUnit(ready.actions, isReservation = true)
                availableLocalProducers += ready.placedTargets
            }
        }

        if (naturalGroups.isEmpty()) appendUnit(emptyList(), isReservation = false)
        for (group in naturalGroups) {
            naturalChunk += group.actions
            availableLocalProducers += group.placedTargets
            drainReadyReservations(allowUnitEndFallback = false)
        }
        flushNaturalChunk()
        drainReadyReservations(allowUnitEndFallback = true)
        require(pendingReservations.isEmpty()) {
            "reservation dependencies could not be ordered for trigger unit $triggerUnitIndex: " +
                pendingReservations.map { it.groupId to it.dependencies }
        }
    }
    return executable
}
