package com.nubasu.nuchematica.renderer.section

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class SectionMeshStateTest {

    @Test
    public fun staleEpochAndGenerationNeverReachUpload(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val staleEpoch = fixture.state.beginGeometry(KEY_A)!!

        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))

        var uploads = 0
        assertEquals(
            SectionApplyResult.STALE,
            fixture.state.applyGeometry(staleEpoch, 10L, 1, null) {
                uploads++
                allocation(FakeHandle("stale-epoch"), 10L)
            },
        )
        val current = fixture.state.beginGeometry(KEY_A)!!
        val staleGeneration = current.copy(geometryGeneration = current.geometryGeneration - 1L)
        assertEquals(
            SectionApplyResult.STALE,
            fixture.state.applyGeometry(staleGeneration, 10L, 1, null) {
                uploads++
                allocation(FakeHandle("stale-generation"), 10L)
            },
        )
        assertEquals(0, uploads)
    }

    @Test
    public fun cameraRevisionsDiscardOldSortAndReuseGeometry(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val translucent = FakeHandle("translucent")
        fixture.applyReady(KEY_A, translucent = translucent, sortPayload = "centers")

        fixture.state.markCameraThresholdCrossed()
        val staleSort = fixture.state.beginSort(KEY_A)!!
        fixture.state.markCameraThresholdCrossed()

        var uploads = 0
        assertEquals(
            SectionApplyResult.STALE,
            fixture.state.applySort(staleSort.token) { uploads++ },
        )
        val snapshot = fixture.state.snapshot(KEY_A)!!
        assertEquals(SectionGeometryState.READY, snapshot.geometryState)
        assertEquals(SectionSortState.DIRTY, snapshot.sortState)
        assertSame(translucent, snapshot.allocation.translucent)
        assertEquals(0, uploads)
    }

    @Test
    public fun successfulResortKeepsTheSameHandleAndDoesNotCloseGeometry(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val solid = FakeHandle("solid")
        val translucent = FakeHandle("translucent")
        fixture.applyReady(KEY_A, solid, translucent, "centers")
        fixture.state.markCameraThresholdCrossed()
        val sort = fixture.state.beginSort(KEY_A)!!

        var uploadedHandle: FakeHandle? = null
        assertEquals(
            SectionApplyResult.APPLIED,
            fixture.state.applySort(sort.token) { uploadedHandle = it },
        )

        val allocation = fixture.state.snapshot(KEY_A)!!.allocation
        assertSame(translucent, uploadedHandle)
        assertSame(solid, allocation.solid)
        assertSame(translucent, allocation.translucent)
        assertEquals(0, solid.closeCount)
        assertEquals(0, translucent.closeCount)
    }

    @Test
    public fun resortUploadFailureClosesOnlyTranslucentAndDirtiesGeometry(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val solid = FakeHandle("solid")
        val translucent = FakeHandle("translucent")
        fixture.applyReady(KEY_A, solid, translucent, "centers")
        fixture.state.markCameraThresholdCrossed()
        val sort = fixture.state.beginSort(KEY_A)!!

        assertEquals(
            SectionApplyResult.FAILED,
            fixture.state.applySort(sort.token) { throw IllegalStateException("upload") },
        )

        val snapshot = fixture.state.snapshot(KEY_A)!!
        assertEquals(SectionGeometryState.DIRTY, snapshot.geometryState)
        assertEquals(SectionSortState.CLEAN, snapshot.sortState)
        assertSame(solid, snapshot.allocation.solid)
        assertNull(snapshot.allocation.translucent)
        assertEquals(0, solid.closeCount)
        assertEquals(1, translucent.closeCount)
    }

    @Test
    public fun removedSectionsCloseImmediatelyAndSurvivorsSwapAfterUpload(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B))
        val removed = FakeHandle("removed")
        val oldSurvivor = FakeHandle("old-survivor")
        fixture.applyReady(KEY_A, solid = removed)
        fixture.applyReady(KEY_B, solid = oldSurvivor)

        fixture.state.replaceSections(mapOf(KEY_B to BOX_B))

        assertEquals(1, removed.closeCount)
        assertEquals(0, oldSurvivor.closeCount)
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_B)!!.geometryState)

        val replacement = FakeHandle("replacement")
        val token = fixture.state.beginGeometry(KEY_B)!!
        assertEquals(
            SectionApplyResult.APPLIED,
            fixture.state.applyGeometry(token, 10L, 1, null) {
                assertEquals(0, oldSurvivor.closeCount, "old handle must live through upload")
                allocation(replacement, 10L)
            },
        )
        assertEquals(1, oldSurvivor.closeCount)
        assertSame(replacement, fixture.state.snapshot(KEY_B)!!.allocation.solid)
    }

    @Test
    public fun transformEpochKeepsOldHandleUntilReplacementSucceeds(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val old = FakeHandle("old-transform")
        fixture.applyReady(KEY_A, solid = old)
        val epochBefore = fixture.state.meshEpoch
        val movedBox = AABB(10.0, 0.0, 0.0, 26.0, 16.0, 16.0)

        fixture.state.updateTransform(mapOf(KEY_A to movedBox))

        assertTrue(fixture.state.meshEpoch > epochBefore)
        assertEquals(movedBox, fixture.state.worldAabb(KEY_A))
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_A)!!.geometryState)
        assertSame(old, fixture.state.snapshot(KEY_A)!!.allocation.solid)
        assertEquals(0, old.closeCount)

        fixture.applyReady(KEY_A, solid = FakeHandle("new-transform"))
        assertEquals(1, old.closeCount)
    }

    @Test
    public fun closeIsTerminalAndLateCompletionCannotReviveTheMesh(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val token = fixture.state.beginGeometry(KEY_A)!!

        fixture.state.close()
        var uploads = 0
        assertEquals(
            SectionApplyResult.STALE,
            fixture.state.applyGeometry(token, 10L, 1, null) {
                uploads++
                allocation(FakeHandle("late"), 10L)
            },
        )
        fixture.state.replaceSections(mapOf(KEY_B to BOX_B))

        assertTrue(fixture.state.isClosed)
        assertTrue(fixture.state.sectionKeys.isEmpty())
        assertEquals(0, uploads)
    }

    @Test
    public fun geometryFailureRetriesOnceThenStopsWithoutDroppingOldHandle(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val old = FakeHandle("old")
        fixture.applyReady(KEY_A, solid = old)
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))

        val first = fixture.state.beginGeometry(KEY_A)!!
        assertEquals(
            SectionApplyResult.FAILED,
            fixture.state.applyGeometry(first, 10L, 1, null) {
                throw IllegalStateException("first")
            },
        )
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_A)!!.geometryState)

        val second = fixture.state.beginGeometry(KEY_A)!!
        assertEquals(
            SectionApplyResult.FAILED,
            fixture.state.applyGeometry(second, 10L, 1, null) {
                throw IllegalStateException("second")
            },
        )

        val snapshot = fixture.state.snapshot(KEY_A)!!
        assertEquals(SectionGeometryState.FAILED, snapshot.geometryState)
        assertSame(old, snapshot.allocation.solid)
        assertEquals(0, old.closeCount)
        assertEquals(1, fixture.errors.size)
    }

    @Test
    public fun geometryBuiltAgainstOldCameraRevisionQueuesOnlyAResort(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val geometry = fixture.state.beginGeometry(KEY_A)!!

        fixture.state.markCameraThresholdCrossed()
        assertEquals(
            SectionApplyResult.APPLIED,
            fixture.state.applyGeometry(geometry, 10L, 1, "centers") {
                translucentAllocation(FakeHandle("translucent"), 10L)
            },
        )

        val snapshot = fixture.state.snapshot(KEY_A)!!
        assertEquals(SectionGeometryState.READY, snapshot.geometryState)
        assertEquals(SectionSortState.DIRTY, snapshot.sortState)
    }

    @Test
    public fun cameraMovementDirtiesSortOnlyAndOpacityHasNoStateTransition(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        fixture.applyReady(
            KEY_A,
            solid = FakeHandle("solid"),
            translucent = FakeHandle("translucent"),
            sortPayload = "centers",
        )
        val beforeOpacityOnlyDraw = fixture.state.snapshot(KEY_A)

        val afterOpacityOnlyDraw = fixture.state.snapshot(KEY_A)
        assertEquals(beforeOpacityOnlyDraw, afterOpacityOnlyDraw)

        fixture.state.markCameraThresholdCrossed()
        val afterCamera = fixture.state.snapshot(KEY_A)!!
        assertEquals(SectionGeometryState.READY, afterCamera.geometryState)
        assertEquals(SectionSortState.DIRTY, afterCamera.sortState)
    }

    @Test
    public fun softBudgetEvictsOnlyTheOffscreenLeastRecentlyVisibleSection(): Unit {
        val fixture = Fixture(SectionGpuBudget(softBytes = 100L, hardBytes = 200L, hardHandleCount = 10))
        fixture.state.replaceSections(
            mapOf(KEY_A to BOX_A, KEY_B to BOX_B, KEY_C to BOX_C),
        )
        val visible = FakeHandle("visible")
        val lru = FakeHandle("lru")
        fixture.applyReady(KEY_A, solid = visible, bytes = 40L)
        fixture.applyReady(KEY_B, solid = lru, bytes = 40L)
        fixture.state.updateVisibility(setOf(KEY_A), frame = 5L)

        fixture.applyReady(KEY_C, solid = FakeHandle("new"), bytes = 40L)

        assertEquals(0, visible.closeCount)
        assertEquals(1, lru.closeCount)
        assertEquals(SectionGeometryState.READY, fixture.state.snapshot(KEY_A)!!.geometryState)
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_B)!!.geometryState)
        assertEquals(80L, fixture.state.residentSnapshot().bytes)
    }

    @Test
    public fun allocationsWithinSoftBudgetDoNotEvictAnything(): Unit {
        val fixture = Fixture(SectionGpuBudget(softBytes = 100L, hardBytes = 200L, hardHandleCount = 10))
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B))
        val first = FakeHandle("first")
        val second = FakeHandle("second")

        fixture.applyReady(KEY_A, solid = first, bytes = 40L)
        fixture.applyReady(KEY_B, solid = second, bytes = 40L)

        assertEquals(0, first.closeCount)
        assertEquals(0, second.closeCount)
        assertEquals(80L, fixture.state.residentSnapshot().bytes)
        assertEquals(2, fixture.state.residentSnapshot().handleCount)
    }

    @Test
    public fun hardHandleCountEvictsOffscreenBeforeUploadAndNeverExceedsTheLimit(): Unit {
        val fixture = Fixture(
            SectionGpuBudget(softBytes = 1_000L, hardBytes = 2_000L, hardHandleCount = 1),
        )
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B))
        val evicted = FakeHandle("evicted")
        fixture.applyReady(KEY_A, solid = evicted, bytes = 10L)

        fixture.applyReady(KEY_B, solid = FakeHandle("replacement"), bytes = 10L)

        val resident = fixture.state.residentSnapshot()
        assertEquals(1, evicted.closeCount)
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_A)!!.geometryState)
        assertEquals(1, resident.handleCount)
        assertTrue(resident.maxProjectedHandleCount <= 1)
        assertTrue(resident.maxProjectedBytes <= 2_000L)
    }

    @Test
    public fun hardCountDefersSurvivorReplacementWithoutClosingTheOldHandle(): Unit {
        val fixture = Fixture(
            SectionGpuBudget(softBytes = 1_000L, hardBytes = 2_000L, hardHandleCount = 1),
        )
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val old = FakeHandle("old")
        fixture.applyReady(KEY_A, solid = old, bytes = 10L)
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        val replacement = fixture.state.beginGeometry(KEY_A)!!
        var uploads = 0

        assertEquals(
            SectionApplyResult.DEFERRED,
            fixture.state.applyGeometry(replacement, 10L, 1, null) {
                uploads++
                allocation(FakeHandle("new"), 10L)
            },
        )

        assertEquals(0, uploads)
        assertEquals(0, old.closeCount)
        assertSame(old, fixture.state.snapshot(KEY_A)!!.allocation.solid)
        assertEquals(1, fixture.state.residentSnapshot().handleCount)
    }

    @Test
    public fun hardBudgetDefersUploadAndWarnsOnceForUnshownVisibleSection(): Unit {
        val fixture = Fixture(SectionGpuBudget(softBytes = 50L, hardBytes = 50L, hardHandleCount = 1))
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        fixture.state.updateVisibility(setOf(KEY_A), frame = 1L)
        val token = fixture.state.beginGeometry(KEY_A)!!
        var uploads = 0

        repeat(2) {
            assertEquals(
                SectionApplyResult.DEFERRED,
                fixture.state.applyGeometry(token, 60L, 1, null) {
                    uploads++
                    allocation(FakeHandle("too-large"), 60L)
                },
            )
        }

        assertEquals(0, uploads)
        assertEquals(0L, fixture.state.residentSnapshot().bytes)
        assertEquals(0, fixture.state.residentSnapshot().handleCount)
        assertEquals(1, fixture.warnings.size)
    }

    @Test
    public fun evictionPrefersInactiveSectionsEvenWhenTheyWereVisibleMoreRecently(): Unit {
        val fixture = Fixture(SectionGpuBudget(softBytes = 100L, hardBytes = 200L, hardHandleCount = 10))
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B, KEY_C to BOX_C))
        val inactiveButRecentlyVisible = FakeHandle("inactive-recent")
        val activeButNotVisible = FakeHandle("active-not-visible")
        fixture.applyReady(KEY_A, solid = inactiveButRecentlyVisible, bytes = 40L)
        fixture.applyReady(KEY_B, solid = activeButNotVisible, bytes = 40L)

        // KEY_A is visible (and active) at frame 5, then leaves the render-distance radius at frame 6.
        fixture.state.updateVisibility(visibleKeys = setOf(KEY_A), activeKeys = setOf(KEY_A, KEY_B), frame = 5L)
        fixture.state.updateVisibility(visibleKeys = emptySet(), activeKeys = setOf(KEY_B), frame = 6L)

        fixture.applyReady(KEY_C, solid = FakeHandle("new"), bytes = 40L)

        assertEquals(1, inactiveButRecentlyVisible.closeCount)
        assertEquals(0, activeButNotVisible.closeCount)
        assertEquals(SectionGeometryState.DIRTY, fixture.state.snapshot(KEY_A)!!.geometryState)
        assertEquals(SectionGeometryState.READY, fixture.state.snapshot(KEY_B)!!.geometryState)
    }

    @Test
    public fun nextGeometryCandidateSkipsInactiveDirtySectionsUntilActivated(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A))
        fixture.state.updateVisibility(visibleKeys = emptySet(), activeKeys = emptySet(), frame = 1L)

        assertNull(fixture.state.nextGeometryCandidate(Vec3.ZERO))

        fixture.state.updateVisibility(visibleKeys = emptySet(), activeKeys = setOf(KEY_A), frame = 2L)

        assertEquals(KEY_A, fixture.state.nextGeometryCandidate(Vec3.ZERO))
    }

    @Test
    public fun twoArgumentUpdateVisibilityMarksEverySectionActive(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B))

        fixture.state.updateVisibility(setOf(KEY_A), frame = 1L)

        assertTrue(fixture.state.snapshot(KEY_A)!!.active)
        assertTrue(fixture.state.snapshot(KEY_B)!!.active)
    }

    @Test
    public fun candidatePriorityIsVisibleThenNearest(): Unit {
        val fixture = Fixture()
        fixture.state.replaceSections(mapOf(KEY_A to BOX_A, KEY_B to BOX_B, KEY_C to BOX_C))
        fixture.state.updateVisibility(setOf(KEY_C), frame = 1L)

        assertEquals(KEY_C, fixture.state.nextGeometryCandidate(Vec3.ZERO))
        val visible = fixture.state.beginGeometry(KEY_C)!!
        fixture.state.cancelGeometry(visible)
        fixture.state.updateVisibility(emptySet(), frame = 2L)

        assertEquals(KEY_A, fixture.state.nextGeometryCandidate(Vec3.ZERO))
    }

    private class Fixture(
        budget: SectionGpuBudget = SectionGpuBudget(
            softBytes = 1_000L,
            hardBytes = 2_000L,
            hardHandleCount = 20,
        ),
    ) {
        internal val warnings: MutableList<String> = mutableListOf()
        internal val errors: MutableList<Pair<String, Throwable>> = mutableListOf()
        internal val state: SectionMeshState<FakeHandle, String> = SectionMeshState(
            budget = budget,
            closeHandle = FakeHandle::close,
            warn = warnings::add,
            error = { message, throwable -> errors += message to throwable },
        )

        internal fun applyReady(
            key: SectionKey,
            solid: FakeHandle? = null,
            translucent: FakeHandle? = null,
            sortPayload: String? = null,
            bytes: Long = 10L,
        ): Unit {
            val token = state.beginGeometry(key)!!
            val allocation = when {
                solid != null && translucent != null -> SectionGpuAllocation(
                    solid = solid,
                    solidBytes = bytes / 2L,
                    translucent = translucent,
                    translucentBytes = bytes - bytes / 2L,
                )
                solid != null -> allocation(solid, bytes)
                translucent != null -> translucentAllocation(translucent, bytes)
                else -> SectionGpuAllocation.empty()
            }
            assertEquals(
                SectionApplyResult.APPLIED,
                state.applyGeometry(
                    token = token,
                    plannedBytes = allocation.bytes,
                    plannedHandleCount = allocation.handleCount,
                    sortPayload = sortPayload,
                ) { allocation },
            )
        }
    }

    private class FakeHandle(
        private val name: String,
    ) {
        internal var closeCount: Int = 0
            private set

        internal fun close(): Unit {
            closeCount++
        }

        override fun toString(): String = name
    }

    private companion object {
        private val KEY_A = SectionKey(0, 0, 0)
        private val KEY_B = SectionKey(2, 0, 0)
        private val KEY_C = SectionKey(4, 0, 0)
        private val BOX_A = AABB(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        private val BOX_B = AABB(32.0, 0.0, 0.0, 48.0, 16.0, 16.0)
        private val BOX_C = AABB(64.0, 0.0, 0.0, 80.0, 16.0, 16.0)

        private fun allocation(handle: FakeHandle, bytes: Long): SectionGpuAllocation<FakeHandle> {
            return SectionGpuAllocation(handle, bytes, null, 0L)
        }

        private fun translucentAllocation(
            handle: FakeHandle,
            bytes: Long,
        ): SectionGpuAllocation<FakeHandle> {
            return SectionGpuAllocation(null, 0L, handle, bytes)
        }
    }
}
