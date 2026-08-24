package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

internal class PlanExecutionCursorTest {
    private val stone: BlockState = Blocks.STONE.defaultBlockState()

    private fun emptyReport(): BuildabilityReport {
        return BuildabilityReport(
            directCount = 0,
            scaffoldCount = 0,
            reservedCount = 0,
            excludedCategoryCount = 0,
            excludedOccupiedCount = 0,
            excludedFallingCount = 0,
            unreachableCount = 0,
            alreadyPlacedCount = 0,
            excludedPositions = emptyList(),
            unreachablePositions = emptyList(),
            scaffoldMaterialPositions = emptyList(),
        )
    }

    private fun planOf(
        units: List<PlanUnit>,
        reservations: Map<Int, List<PlanAction>> = emptyMap(),
    ): PrintPlan {
        return PrintPlan(units = units, reservations = reservations, report = emptyReport())
    }

    private fun unitOf(band: Int, actions: List<PlanAction>): PlanUnit {
        return PlanUnit(band = band, tileX = 0, tileZ = 0, actions = actions)
    }

    private fun PlanExecutionCursor.submitSoleFrontier(): CursorAction {
        return submitSpecific(orderedFrontier().single())!!
    }

    private fun PlanExecutionCursor.submitSoleFrontierOrNull(): CursorAction? {
        val frontier = orderedFrontier()
        if (frontier.isEmpty()) return null
        return submitSpecific(frontier.single())
    }

    @Test
    internal fun emptyPlanIsImmediatelyComplete() {
        val cursor = PlanExecutionCursor(planOf(units = emptyList()))

        assertTrue(cursor.status().isComplete)
        assertEquals(0, cursor.status().currentUnitIndex)
        assertEquals(emptyList<Long>(), cursor.orderedFrontier())
    }

    @Test
    internal fun midGroupScaffoldFailureSkipsRestAndStillRequiresPlacedScaffoldRemoval() {
        val s1 = BlockPos(0, 1, 0)
        val s2 = BlockPos(0, 2, 0)
        val target = BlockPos(0, 3, 0)
        val actions = listOf(
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceScaffold(s2, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(s2, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val first = cursor.submitSoleFrontier()
        assertEquals(s1, (first.action as PlanAction.PlaceScaffold).pos)
        cursor.onEvent(CursorEvent.Accepted(first.actionId, first.attemptId))

        val second = cursor.submitSoleFrontier()
        assertEquals(s2, (second.action as PlanAction.PlaceScaffold).pos)
        cursor.onEvent(CursorEvent.ItemUnavailable(second.actionId, second.attemptId))

        val third = cursor.submitSoleFrontier()
        assertEquals(s1, (third.action as PlanAction.RemoveScaffold).pos)
        assertFalse(cursor.status().isComplete)

        cursor.onEvent(CursorEvent.RemoveConfirmed(third.actionId, third.attemptId))

        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(2, status.doneCount)
        assertEquals(1, status.failedCount)
        assertEquals(2, status.skippedCount)
    }

    @Test
    internal fun anchorScaffoldFailureClosesUnitWithNoSuccessfulPlacement() {
        val anchor = BlockPos(5, 1, 5)
        val target = BlockPos(5, 2, 5)
        val actions = listOf(
            PlanAction.PlaceScaffold(anchor, groupId = 7),
            PlanAction.PlaceTarget(target, stone, groupId = 7),
            PlanAction.RemoveScaffold(anchor, groupId = 7),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val submitted = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.SubmitFailed(submitted.actionId, submitted.attemptId))

        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
        assertEquals(2, status.skippedCount)
        assertEquals(0, status.doneCount)
        assertEquals(emptyList<Long>(), cursor.orderedFrontier())
    }

    @Test
    internal fun rejectedExhaustsRetryBudgetThenBecomesTerminal() {
        val pos = BlockPos(9, 1, 9)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        var submissions = 0
        while (!cursor.status().isComplete) {
            val action = cursor.submitSoleFrontierOrNull() ?: break
            submissions++
            cursor.onEvent(CursorEvent.Rejected(action.actionId, action.attemptId))
        }

        assertEquals(PrinterRuntime.MAX_RETRIES + 1, submissions)
        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
    }

    @Test
    internal fun completionAuthorityExecutesReservationsAfterTheirTriggerUnit() {
        val posA = BlockPos(0, 1, 0)
        val posB = BlockPos(0, 2, 0)
        val reserved = BlockPos(9, 9, 9)
        val plan = planOf(
            units = listOf(
                unitOf(0, listOf(PlanAction.PlaceTarget(posA, stone, groupId = 1))),
                unitOf(1, listOf(PlanAction.PlaceTarget(posB, stone, groupId = 2))),
            ),
            reservations = mapOf(
                0 to listOf(
                    PlanAction.PlaceTarget(reserved, stone, dependsOn = listOf(posA), groupId = 3),
                ),
            ),
        )
        val cursor = PlanExecutionCursor(plan)

        val firstStatus = cursor.status()
        assertEquals(0, firstStatus.currentUnitIndex)
        assertFalse(firstStatus.isComplete)

        val first = cursor.submitSoleFrontier()
        assertEquals(0, first.unitIndex)
        cursor.onEvent(CursorEvent.Accepted(first.actionId, first.attemptId))

        val secondStatus = cursor.status()
        assertEquals(1, secondStatus.currentUnitIndex)
        assertFalse(secondStatus.isComplete)

        val second = cursor.submitSoleFrontier()
        assertEquals(reserved, (second.action as PlanAction.PlaceTarget).pos)
        assertEquals(1, second.unitIndex)
        cursor.onEvent(CursorEvent.Accepted(second.actionId, second.attemptId))

        val thirdStatus = cursor.status()
        assertEquals(2, thirdStatus.currentUnitIndex)
        assertFalse(thirdStatus.isComplete)

        val third = cursor.submitSoleFrontier()
        assertEquals(posB, (third.action as PlanAction.PlaceTarget).pos)
        assertEquals(2, third.unitIndex)
        cursor.onEvent(CursorEvent.Accepted(third.actionId, third.attemptId))

        val finalStatus = cursor.status()
        assertTrue(finalStatus.isComplete)
        assertEquals(3, finalStatus.currentUnitIndex)
        assertEquals(3, finalStatus.doneCount)
    }

    @Test
    internal fun reservationRunsImmediatelyAfterItsSupportGroupWithinTheTriggerUnit() {
        val before = BlockPos(0, 1, 0)
        val support = BlockPos(0, 1, 1)
        val after = BlockPos(0, 1, 2)
        val reserved = BlockPos(1, 1, 1)
        val plan = planOf(
            units = listOf(
                unitOf(
                    0,
                    listOf(
                        PlanAction.PlaceTarget(before, stone, groupId = 1),
                        PlanAction.PlaceTarget(support, stone, groupId = 2),
                        PlanAction.PlaceTarget(after, stone, groupId = 3),
                    ),
                ),
            ),
            reservations = mapOf(
                0 to listOf(
                    PlanAction.PlaceTarget(reserved, stone, dependsOn = listOf(support), groupId = 4),
                ),
            ),
        )

        val executablePositions = executablePlanUnits(plan).map { unit ->
            unit.actions.mapNotNull { action -> (action as? PlanAction.PlaceTarget)?.pos }
        }

        assertEquals(listOf(listOf(before, support), listOf(reserved), listOf(after)), executablePositions)
    }

    @Test
    internal fun delayedEventAfterTerminalIsIgnoredNotThrown() {
        val pos = BlockPos(3, 1, 3)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val action = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Accepted(action.actionId, action.attemptId))
        val statusBefore = cursor.status()

        cursor.onEvent(CursorEvent.Submitted(action.actionId, action.attemptId))
        cursor.onEvent(CursorEvent.Rejected(action.actionId, action.attemptId))

        assertEquals(statusBefore.copy(ignoredEventCount = 2), cursor.status())
    }

    @Test
    internal fun unknownActionIdThrows() {
        val cursor = PlanExecutionCursor(planOf(units = emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            cursor.onEvent(CursorEvent.Accepted(actionId = 0L, attemptId = 0L))
        }
    }

    @Test
    internal fun nonEmptyDependsOnOnAUnitActionIsRejectedAtConstruction() {
        val pos = BlockPos(0, 1, 0)
        val support = BlockPos(0, 0, 0)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, dependsOn = listOf(support), groupId = 1))

        assertThrows(IllegalStateException::class.java) {
            PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        }
    }

    @Test
    internal fun sameInputsAndEventSequenceProduceIdenticalOutcomes() {
        fun buildPlan(): PrintPlan {
            val s1 = BlockPos(0, 1, 0)
            val target = BlockPos(0, 2, 0)
            val other = BlockPos(1, 1, 0)
            val actions = listOf(
                PlanAction.PlaceScaffold(s1, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(s1, groupId = 1),
                PlanAction.PlaceTarget(other, stone, groupId = 2),
            )
            return planOf(units = listOf(unitOf(0, actions)))
        }

        fun run(): Pair<List<Long>, CursorStatus> {
            val cursor = PlanExecutionCursor(buildPlan())
            val submittedIds = mutableListOf<Long>()
            var guard = 0
            while (!cursor.status().isComplete && guard < 20) {
                guard++
                val frontierId = cursor.orderedFrontier()
                    .firstOrNull { id -> cursor.stateOf(id) !is ActionState.InFlight } ?: break
                val action = cursor.submitSpecific(frontierId) ?: break
                submittedIds += action.actionId
                cursor.onEvent(CursorEvent.Waiting(action.actionId, action.attemptId, WaitingReason.WAITING_FOR_REACH))
                val retried = cursor.submitSpecific(frontierId)!!
                submittedIds += retried.actionId
                cursor.onEvent(CursorEvent.Accepted(retried.actionId, retried.attemptId))
            }
            return submittedIds to cursor.status()
        }

        val (idsA, statusA) = run()
        val (idsB, statusB) = run()

        assertEquals(idsA, idsB)
        assertEquals(statusA, statusB)
        assertTrue(statusA.isComplete)
    }

    @Test
    internal fun postCleanupMismatchOverridesAnAlreadyDonePlaceTarget() {
        val target = BlockPos(4, 1, 4)
        val actions = listOf(PlanAction.PlaceTarget(target, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val submitted = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Accepted(submitted.actionId, submitted.attemptId))
        assertTrue(cursor.status().isComplete)
        assertEquals(1, cursor.status().doneCount)

        cursor.onEvent(CursorEvent.PostCleanupMismatch(submitted.actionId))

        val status = cursor.status()
        assertEquals(0, status.doneCount)
        assertEquals(1, status.failedCount)
        assertTrue(status.isComplete)
    }

    @Test
    internal fun postCleanupMismatchOnLiveActionIsOrdinaryTerminalFailureWithCascade() {
        val s1 = BlockPos(6, 1, 6)
        val target = BlockPos(6, 2, 6)
        val actions = listOf(
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val scaffold = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Accepted(scaffold.actionId, scaffold.attemptId))

        val place = cursor.submitSoleFrontier()
        assertEquals(target, (place.action as PlanAction.PlaceTarget).pos)
        cursor.onEvent(CursorEvent.PostCleanupMismatch(place.actionId))

        val remove = cursor.submitSoleFrontier()
        assertEquals(s1, (remove.action as PlanAction.RemoveScaffold).pos)
        cursor.onEvent(CursorEvent.RemoveConfirmed(remove.actionId, remove.attemptId))

        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
        assertEquals(2, status.doneCount)
    }

    @Test
    internal fun postCleanupMismatchOnInFlightActionClearsAwaitingAttemptSoTheRealOutcomeIsTallied() {
        val target = BlockPos(7, 1, 7)
        val actions = listOf(PlanAction.PlaceTarget(target, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val submitted = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.PostCleanupMismatch(submitted.actionId))
        val statusAfterMismatch = cursor.status()
        assertTrue(statusAfterMismatch.isComplete)
        assertEquals(1, statusAfterMismatch.failedCount)
        assertEquals(0, statusAfterMismatch.ignoredEventCount)

        cursor.onEvent(CursorEvent.Accepted(submitted.actionId, submitted.attemptId))

        val status = cursor.status()
        assertEquals(1, status.failedCount)
        assertEquals(0, status.doneCount)
        assertEquals(1, status.ignoredEventCount)
    }

    @Test
    internal fun timeoutOnAScaffoldCellKeepsItsRemovalMandatory() {
        val s1 = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val actions = listOf(
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        repeat(PrinterRuntime.MAX_RETRIES + 1) {
            val next = cursor.submitSoleFrontier()
            cursor.onEvent(CursorEvent.Timeout(next.actionId, next.attemptId))
        }

        val remove = cursor.submitSoleFrontier()
        assertEquals(s1, (remove.action as PlanAction.RemoveScaffold).pos)
        cursor.onEvent(CursorEvent.RemoveConfirmed(remove.actionId, remove.attemptId))

        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
        assertEquals(1, status.skippedCount)
        assertEquals(1, status.doneCount)
    }

    @Test
    internal fun duplicateReportsForTheSameAttemptConsumeOnlyOneRetry() {
        val pos = BlockPos(9, 1, 9)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val first = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))
        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))
        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))

        var submissions = 1
        while (!cursor.status().isComplete) {
            val action = cursor.submitSoleFrontierOrNull() ?: break
            submissions++
            cursor.onEvent(CursorEvent.Rejected(action.actionId, action.attemptId))
        }

        assertEquals(PrinterRuntime.MAX_RETRIES + 1, submissions)
        assertEquals(2, cursor.status().ignoredEventCount)
    }

    @Test
    internal fun staleAttemptReportDuringAResubmissionCannotDoubleSubmit() {
        val pos = BlockPos(8, 1, 8)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val first = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))

        val second = cursor.submitSoleFrontier()
        assertEquals(first.actionId, second.actionId)

        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))

        val stillOutstandingId = cursor.orderedFrontier().single()
        assertEquals(second.actionId, stillOutstandingId)
        assertEquals(null, cursor.submitSpecific(stillOutstandingId))

        cursor.onEvent(CursorEvent.Accepted(second.actionId, second.attemptId))
        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.doneCount)
        assertEquals(1, status.ignoredEventCount)
    }

    @Test
    internal fun eventAfterUnitBoundaryAdvancesIsIgnoredAndDoesNotDisturbLaterUnit() {
        val posA = BlockPos(0, 1, 0)
        val posB = BlockPos(0, 2, 0)
        val plan = planOf(
            units = listOf(
                unitOf(0, listOf(PlanAction.PlaceTarget(posA, stone, groupId = 1))),
                unitOf(1, listOf(PlanAction.PlaceTarget(posB, stone, groupId = 2))),
            ),
        )
        val cursor = PlanExecutionCursor(plan)

        val first = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Accepted(first.actionId, first.attemptId))
        assertEquals(1, cursor.status().currentUnitIndex)

        val second = cursor.submitSoleFrontier()
        assertEquals(1, second.unitIndex)

        cursor.onEvent(CursorEvent.Rejected(first.actionId, first.attemptId))

        assertEquals(1, cursor.status().currentUnitIndex)
        assertEquals(1, cursor.status().ignoredEventCount)

        cursor.onEvent(CursorEvent.Accepted(second.actionId, second.attemptId))
        val status = cursor.status()
        assertTrue(status.isComplete)
        assertEquals(2, status.doneCount)
        assertEquals(1, status.ignoredEventCount)
    }

    @Test
    internal fun waitingDetourDoesNotConsumeRetryBudget() {
        val pos = BlockPos(2, 1, 2)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val first = cursor.submitSoleFrontier()
        cursor.onEvent(CursorEvent.Waiting(first.actionId, first.attemptId, WaitingReason.WAITING_FOR_REACH))

        val second = cursor.submitSoleFrontier()
        assertEquals(first.actionId, second.actionId)
        cursor.onEvent(CursorEvent.Rejected(second.actionId, second.attemptId))

        var submissions = 2
        while (!cursor.status().isComplete) {
            val action = cursor.submitSoleFrontierOrNull() ?: break
            submissions++
            cursor.onEvent(CursorEvent.Rejected(action.actionId, action.attemptId))
        }

        assertEquals(PrinterRuntime.MAX_RETRIES + 2, submissions)
    }

    @Test
    internal fun groupShapeWithRemovalsNotInReverseOrderIsRejectedAtConstruction() {
        val s1 = BlockPos(0, 1, 0)
        val s2 = BlockPos(0, 2, 0)
        val target = BlockPos(0, 3, 0)
        val actions = listOf(
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceScaffold(s2, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
            PlanAction.RemoveScaffold(s2, groupId = 1),
        )

        assertThrows(IllegalStateException::class.java) {
            PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        }
    }

    @Test
    internal fun groupShapeWithDuplicateScaffoldPositionsIsRejectedAtConstruction() {
        val s1 = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val actions = listOf(
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceScaffold(s1, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
            PlanAction.RemoveScaffold(s1, groupId = 1),
        )

        assertThrows(IllegalStateException::class.java) {
            PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        }
    }

    @Test
    internal fun orderedFrontierListsOneFrontierPerGroupInFirstAppearanceOrder() {
        val posA = BlockPos(1, 1, 1)
        val posB = BlockPos(2, 1, 1)
        val actions = listOf(
            PlanAction.PlaceTarget(posA, stone, groupId = 5),
            PlanAction.PlaceTarget(posB, stone, groupId = 3),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        assertEquals(listOf(0L, 1L), cursor.orderedFrontier())
    }

    @Test
    internal fun orderedFrontierIncludesAnInFlightGroupFrontier() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        cursor.submitSpecific(0L)

        assertEquals(listOf(0L), cursor.orderedFrontier())
        assertTrue(cursor.stateOf(0L) is ActionState.InFlight)
    }

    @Test
    internal fun submitSpecificSubmitsAPendingFrontierAction() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val submitted = cursor.submitSpecific(0L)

        assertEquals(0L, submitted?.actionId)
        assertEquals(0L, submitted?.attemptId)
        assertTrue(cursor.stateOf(0L) is ActionState.InFlight)
    }

    @Test
    internal fun submitSpecificSubmitsAWaitingFrontierActionWithAFreshAttemptId() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        val first = cursor.submitSpecific(0L)!!
        cursor.onEvent(CursorEvent.Waiting(first.actionId, first.attemptId, WaitingReason.WAITING_FOR_REACH))

        val second = cursor.submitSpecific(0L)

        assertEquals(0L, second?.actionId)
        assertEquals(1L, second?.attemptId)
        assertTrue(cursor.stateOf(0L) is ActionState.InFlight)
    }

    @Test
    internal fun submitSpecificRejectsAnActionInALaterUnit() {
        val posA = BlockPos(0, 1, 0)
        val posB = BlockPos(0, 2, 0)
        val plan = planOf(
            units = listOf(
                unitOf(0, listOf(PlanAction.PlaceTarget(posA, stone, groupId = 1))),
                unitOf(1, listOf(PlanAction.PlaceTarget(posB, stone, groupId = 2))),
            ),
        )
        val cursor = PlanExecutionCursor(plan)

        val result = cursor.submitSpecific(1L)

        assertEquals(null, result)
        assertTrue(cursor.stateOf(1L) is ActionState.Pending)
    }

    @Test
    internal fun submitSpecificRejectsANonFrontierAction() {
        val scaffold = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val actions = listOf(
            PlanAction.PlaceScaffold(scaffold, groupId = 1),
            PlanAction.PlaceTarget(target, stone, groupId = 1),
            PlanAction.RemoveScaffold(scaffold, groupId = 1),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val result = cursor.submitSpecific(1L)

        assertEquals(null, result)
        assertTrue(cursor.stateOf(0L) is ActionState.Pending)
        assertTrue(cursor.stateOf(1L) is ActionState.Pending)
    }

    @Test
    internal fun submitSpecificRejectsATerminalAction() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        val submitted = cursor.submitSpecific(0L)!!
        cursor.onEvent(CursorEvent.Accepted(submitted.actionId, submitted.attemptId))

        val result = cursor.submitSpecific(0L)

        assertEquals(null, result)
        assertTrue(cursor.stateOf(0L) is ActionState.Done)
    }

    @Test
    internal fun submitSpecificRejectsAnAlreadyInFlightAction() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        cursor.submitSpecific(0L)

        val result = cursor.submitSpecific(0L)

        assertEquals(null, result)
        assertTrue(cursor.stateOf(0L) is ActionState.InFlight)
    }

    @Test
    internal fun submitSpecificAllowsASiblingGroupFrontierWhileAnotherGroupIsInFlight() {
        val posA = BlockPos(1, 1, 1)
        val posB = BlockPos(2, 1, 1)
        val actions = listOf(
            PlanAction.PlaceTarget(posA, stone, groupId = 1),
            PlanAction.PlaceTarget(posB, stone, groupId = 2),
        )
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))
        cursor.submitSpecific(0L)
        assertTrue(cursor.stateOf(0L) is ActionState.InFlight)

        val result = cursor.submitSpecific(1L)

        assertEquals(1L, result?.actionId)
        assertTrue(cursor.stateOf(1L) is ActionState.InFlight)
    }

    @Test
    internal fun submitSpecificRejectsAnUnknownActionId() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        assertEquals(null, cursor.submitSpecific(99L))
    }

    @Test
    internal fun submitSpecificRejectsAnActionIdThatWouldAliasViaIntTruncation() {
        val pos = BlockPos(1, 1, 1)
        val actions = listOf(PlanAction.PlaceTarget(pos, stone, groupId = 1))
        val cursor = PlanExecutionCursor(planOf(units = listOf(unitOf(0, actions))))

        val result = cursor.submitSpecific(0x1_0000_0000L)

        assertEquals(null, result)
        assertTrue(cursor.stateOf(0L) is ActionState.Pending)
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

internal class ShouldAutoRetryPlanSessionTest {
    private fun statusOf(
        isComplete: Boolean,
        failedCount: Int = 0,
        skippedCount: Int = 0,
    ): CursorStatus {
        return CursorStatus(
            pendingCount = 0,
            waitingCount = 0,
            inFlightCount = 0,
            doneCount = 0,
            failedCount = failedCount,
            skippedCount = skippedCount,
            currentUnitIndex = 0,
            isComplete = isComplete,
            ignoredEventCount = 0,
        )
    }

    @Test
    internal fun completeWithFailuresAndUnusedRetryReturnsTrue() {
        val status = statusOf(isComplete = true, failedCount = 1)
        assertTrue(shouldAutoRetryPlanSession(status, hasReservations = false, retryUsed = false))
    }

    @Test
    internal fun completeWithReservationsAndUnusedRetryReturnsTrue() {
        val status = statusOf(isComplete = true)
        assertTrue(shouldAutoRetryPlanSession(status, hasReservations = true, retryUsed = false))
    }

    @Test
    internal fun completeWithLiveMismatchAndUnusedRetryReturnsTrue() {
        val status = statusOf(isComplete = true)
        assertTrue(
            shouldAutoRetryPlanSession(
                status,
                hasReservations = false,
                hasLiveMismatches = true,
                retryUsed = false,
            ),
        )
    }

    @Test
    internal fun completeAndCleanReturnsFalse() {
        val status = statusOf(isComplete = true)
        assertFalse(shouldAutoRetryPlanSession(status, hasReservations = false, retryUsed = false))
    }

    @Test
    internal fun incompleteReturnsFalseRegardlessOfFailuresOrReservations() {
        val status = statusOf(isComplete = false, failedCount = 1, skippedCount = 1)
        assertFalse(shouldAutoRetryPlanSession(status, hasReservations = true, retryUsed = false))
    }

    @Test
    internal fun retryAlreadyUsedReturnsFalse() {
        val status = statusOf(isComplete = true, failedCount = 1)
        assertFalse(shouldAutoRetryPlanSession(status, hasReservations = true, retryUsed = true))
    }

    @Test
    internal fun liveMismatchDoesNotGrantASecondRetry() {
        val status = statusOf(isComplete = true)
        assertFalse(
            shouldAutoRetryPlanSession(
                status,
                hasReservations = false,
                hasLiveMismatches = true,
                retryUsed = true,
            ),
        )
    }
}

internal class PlanCompletionReconciliationTest {
    @Test
    internal fun refreshesEverySchematicCellButCountsOnlyMismatchedPlannedTargets(): Unit {
        val localPlannedMissing = BlockPos(0, 0, 0)
        val localPlannedPresent = BlockPos(1, 0, 0)
        val localUnplannedMissing = BlockPos(2, 0, 0)
        val offset = BlockPos(10, 20, 30)
        val stone = Blocks.STONE.defaultBlockState()
        val oak = Blocks.OAK_PLANKS.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val liveWorld = mapOf(
            localPlannedMissing.offset(offset) to air,
            localPlannedPresent.offset(offset) to oak,
            localUnplannedMissing.offset(offset) to air,
        )
        val observed = mutableListOf<Pair<BlockPos, BlockState>>()

        val reconciliation = reconcilePlanCompletion(
            schematicLocalPositions = listOf(localPlannedMissing, localPlannedPresent, localUnplannedMissing),
            plannedTargets = listOf(
                PlanTargetExpectation(localPlannedMissing.offset(offset), stone),
                PlanTargetExpectation(localPlannedPresent.offset(offset), oak),
            ),
            localToWorld = { local -> local.offset(offset) },
            liveStateAt = liveWorld::getValue,
            onObserved = { pos, state -> observed += pos to state },
            matches = { expected, actual -> expected == actual },
        )

        assertEquals(
            listOf(PlanTargetExpectation(localPlannedMissing.offset(offset), stone)),
            reconciliation.mismatchedTargets,
        )
        assertEquals(2, reconciliation.targetCount)
        assertEquals(liveWorld.entries.map { it.key to it.value }, observed)
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
