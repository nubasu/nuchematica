package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

// One executable step of a plan unit's (or a resolved reservation's) action list, in the
// order PrintPlanner assigned it. A scaffold-assisted target is always place scaffold(s)
// -- in chain order, anchor first -- then PlaceTarget, then RemoveScaffold in reverse
// (target-adjacent cell first), so the temporary material never outlives its own target.
// dependsOn is only ever non-empty for a reservation-resolved PlaceTarget/PlaceScaffold:
// the support position(s) that must be world-confirmed at their expected state before this
// action is submitted (a natural-slot DIRECT/SCAFFOLD placement's own unit already
// sequences its support ahead of it, so it never needs this). A candidate-resolved
// reservation's PlaceTarget depends on exactly the neighbor it was resolved against; a
// chain-resolved reservation depends on its scaffold anchor's own chosen support cell, and
// only when that cell is itself schematic content -- a pre-existing frozen-world block
// needs no such check. That same dependency also gates the anchor PlaceScaffold action
// itself (the first cell of the chain): the anchor is the one chain cell placed directly
// against the support, so it is the one action that actually needs the support to exist
// first -- every later chain cell and the removal steps already sequence off the anchor.
// groupId ties every action belonging to the same real target's scaffold transaction
// (its own PlaceScaffold cells, its PlaceTarget, and their reverse-order RemoveScaffold
// cells) together under one plan-wide-unique positive integer; a target with no scaffold
// at all (a plain PlaceTarget) still gets its own unique groupId, one per real target the
// plan schedules. 0 is never assigned by PrintPlanner -- it exists only as this field's
// unset default.
internal sealed interface PlanAction {
    data class PlaceTarget(
        internal val pos: BlockPos,
        internal val expected: BlockState,
        internal val dependsOn: List<BlockPos> = emptyList(),
        internal val groupId: Int = 0,
        // Where and how the executor should stand to place this target (feet position,
        // clicked face, exact hit vector). Schema reserved ahead of the executor milestone
        // that fills it in -- always null from PrintPlanner today.
        internal val stance: PlannedStance? = null,
    ) : PlanAction
    data class PlaceScaffold(
        internal val pos: BlockPos,
        internal val dependsOn: List<BlockPos> = emptyList(),
        internal val groupId: Int = 0,
    ) : PlanAction
    data class RemoveScaffold(internal val pos: BlockPos, internal val groupId: Int = 0) : PlanAction
}

// Where an executor should stand and click to place a PlaceTarget action -- feet position,
// the clicked face, and the exact hit vector, mirroring PrinterCandidate's own hit/rotation
// fields. Type only for now: PrintPlanner never fills this in (that is the executor
// milestone's own job), so every PlaceTarget's stance stays null until then.
internal data class PlannedStance(
    internal val feet: BlockPos,
    internal val hitFace: Direction?,
    internal val hit: Vec3?,
)

// Ordered work for one execution unit (a band x tile cell in the bottom-up build order):
// the actions PrintPlanner assigned during that unit's own single-pass classification
// slot (direct placements and scaffold-assisted placements resolved in their natural
// order). Positions this unit's cells only support through a reservation are NOT here --
// see PrintPlan.reservations, keyed by the unit whose completion resolves them instead.
internal data class PlanUnit(
    internal val band: Int,
    internal val tileX: Int,
    internal val tileZ: Int,
    internal val actions: List<PlanAction>,
)

// Why a position was excluded from the plan outright (never a reservation candidate):
// CATEGORY -- eligiblePrinterBlockItem rejects it (unsupported item, double slab, ...).
// OCCUPIED -- the frozen world already holds an unreplaceable, mismatching block there.
// FALLING -- a falling block (sand/gravel/concrete powder) with nothing arriving below
// it by the end of the print, so placing it would just drop it into open air.
internal enum class ExclusionReason { CATEGORY, OCCUPIED, FALLING }

internal data class ExcludedPosition(internal val pos: BlockPos, internal val reason: ExclusionReason)

// Counts and position lists surfaced to the player before a run starts (product
// requirement: show the direct/scaffold/reserved/excluded/unreachable breakdown up
// front). scaffoldCount tallies TARGET positions that needed a scaffold chain, not the
// chain cells themselves -- scaffoldMaterialPositions is the actual temporary cells the
// whole plan places (a single target can need more than one, see
// PrintPlanner.appendScaffoldActions), listed separately since that is the number the
// product requirement (support-material total and positions) actually asks for.
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

// Whether a resolved reservation's own cell needed a scaffold-removal survival check
// (SURVIVAL -- see PrintPlanner's postRemovalView-gated pass) or only ever needed a real
// placement face from a not-yet-decided sibling (SUPPORT -- the plain findReservationSupport
// shape). Mirrors PendingReservation.survivalChain's null-ness, surfaced here since that
// internal type is never itself part of PrintPlan's own output.
internal enum class ReservationOrigin { SUPPORT, SURVIVAL }

// How a resolved reservation actually gets placed: DIRECT means straight against the
// candidate that confirmed it (no scaffold cells at all, same as an ordinary PlaceTarget);
// SCAFFOLD means through a scaffold chain (either the survival-origin chain found at
// classification time, or a fresh retry). Mirrors ReservationOutcome.PLACED_DIRECT/
// PLACED_SCAFFOLD.
internal enum class ReservationMethod { DIRECT, SCAFFOLD }

// One resolved reservation's own metadata, for callers that need to reason about the
// reservation fixpoint's result without re-deriving it from PrintPlan.reservations' action
// lists. Only ever recorded for a reservation that actually resolved (PLACED_DIRECT/
// PLACED_SCAFFOLD) -- a FAILED reservation is already surfaced through
// BuildabilityReport.unreachablePositions, so it is never duplicated here.
internal data class ReservationDetail(
    internal val pos: BlockPos,
    internal val originOrder: Int,
    internal val origin: ReservationOrigin,
    internal val effectiveMethod: ReservationMethod,
    internal val triggerUnit: Int,
    internal val dependsOn: List<BlockPos>,
)

// A full plan-first classification result: bottom-up band x tile units in build order,
// plus reservations (positions whose only viable support at classification time was a
// not-yet-decided sibling cell) keyed by the index into `units` of whichever unit's
// completion resolves them. See PrintPlanner.plan for how both are built.
internal data class PrintPlan(
    internal val units: List<PlanUnit>,
    internal val reservations: Map<Int, List<PlanAction>>,
    internal val report: BuildabilityReport,
    internal val reservationDetails: List<ReservationDetail> = emptyList(),
)
