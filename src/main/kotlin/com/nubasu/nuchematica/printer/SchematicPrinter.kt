package com.nubasu.nuchematica.printer

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import com.nubasu.nuchematica.schematic.MissingBlockChange
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

public object SchematicPrinter {
    // See planNoProgressCount's own doc.
    private const val NO_PROGRESS_BACKOFF_THRESHOLD: Int = 3
    // See planRebuildBackoffTicksRemaining's own doc.
    private const val NO_PROGRESS_REBUILD_BACKOFF_TICKS: Int = 100

    private val activation: PrinterActivation = PrinterActivation()
    private val skipLog: PrinterSkipLog = PrinterSkipLog()
    // TEMP C3DBG: last known skip reason per position, appended
    // to debugDumpPinState's leftover lines so a residual world's non-actionable
    // positions are diagnosable by their most recent skip cause, not just their static
    // classification. Removed alongside the rest of C3DBG once the layer-pin
    // investigation closes.
    private val skipPositionLog: PrinterSkipPositionLog = PrinterSkipPositionLog()
    private val deferralLedger: PrinterDeferralLedger = PrinterDeferralLedger()
    private val scaffoldLedger: ScaffoldLedger = ScaffoldLedger()
    private val runtime: PrinterRuntime = PrinterRuntime(
        skipLog = skipLog,
        // Evaluated only while selecting during a live tick. Keep Minecraft and the
        // missing snapshot inside the lambda body so object initialization remains
        // safe on the modloading worker thread.
        isBlocked = { worldPos ->
            val level = Minecraft.getInstance().level
            // Deferral revalidation reads the frozen-world model,
            // not a live level::getBlockState -- the level null-check stays (still guards
            // "is the world even attached at all"), only the state source changes.
            level != null && deferralLedger.isDeferred(
                worldPos,
                PrintWorldModel::stateAt,
                MissingBlockHolder.missingSnapshot().revision,
            )
        },
        // Same deferral route as the selector's PREDICTION_MISMATCH below: a position
        // the runtime just blocked for exhausting retries must also stop counting as
        // pending work for the layer gate and the mover, or it pins the gate on its
        // layer. The deferral self-evicts when the position's neighborhood changes,
        // which is exactly when a rejected placement deserves a fresh chance.
        onRetryLimitBlocked = { worldPos ->
            // The runtime's own RETRY_LIMIT path never flows
            // through the selector's onSkip below (it fires from PrinterRuntime's
            // completion handling, not candidate selection), so it is recorded here
            // instead.
            skipPositionLog.record(PrinterSkipReason.RETRY_LIMIT, worldPos)
            Minecraft.getInstance().level?.let {
                deferralLedger.defer(worldPos, PrinterDeferralReason.RETRY_LIMIT, PrintWorldModel::stateAt)
            }
        },
        candidateSelector = PrinterCandidateSelector(
            skipLog = skipLog,
            // Fetches the level lazily (only when a skip actually fires during a tick),
            // never at field-initializer time: this selector is constructed as part of
            // runtime's own field initializer, so the lambda body must stay deferred
            // (see layerGateMissingCache below for the same precaution).
            onSkip = { reason, worldPos ->
                // Every skip reason is recorded, not just
                // PREDICTION_MISMATCH -- the deferral below stays reason-gated.
                skipPositionLog.record(reason, worldPos)
                if (reason == PrinterSkipReason.PREDICTION_MISMATCH) {
                    Minecraft.getInstance().level?.let {
                        deferralLedger.defer(worldPos, PrinterDeferralReason.PREDICTION_MISMATCH, PrintWorldModel::stateAt)
                    }
                }
            },
            // Lambda body only touches `context`, never Minecraft.getInstance() or a
            // bound reference: it must stay lazy since this selector is built as part
            // of a field initializer (see layerGateMissingCache below for the same
            // precaution). The context's player is already live for the current tick.
            orientedPrediction = { item, context, expectedState ->
                val contextPlayer = context.player
                if (contextPlayer == null) {
                    null
                } else {
                    resolveOrientedRotation(
                        currentYaw = contextPlayer.yRot,
                        currentPitch = contextPlayer.xRot,
                        setRotation = { yaw, pitch ->
                            contextPlayer.setYRot(yaw)
                            contextPlayer.setXRot(pitch)
                        },
                        matches = {
                            val predicted = item.getPlacementState(context)
                            predicted != null && BlockStateEquivalence.matches(expectedState, predicted)
                        },
                    )
                }
            },
        ),
    )
    private val statusTracker: PrinterStatusTracker = PrinterStatusTracker()
    private val feedSnapshotStore: FeedSnapshotStore = FeedSnapshotStore()
    private val cameraEase: CameraEase = CameraEase()
    private val layerGate: PrinterLayerGate = PrinterLayerGate()
    private val layerGateMissingCache = LayerGateMissingCache(
        eligibleState = { state -> eligiblePrinterBlockItem(state) != null },
        // Lambda, not a bound reference: a bound reference would class-init
        // SchematicRenderManager on whichever thread first touches this object
        // (its mesh thread guard must capture the client main thread).
        localToWorld = { pos -> SchematicRenderManager.localBlockToWorld(pos) },
    )
    private val layerGateEligibleMissingCache = LayerGateEligibleMissingCache(
        entryAt = { localPos -> layerGateMissingCache[localPos] },
    )
    private val layerGateSupportMemo = LayerGateSupportMemo()
    private val layerGateScaffoldMemo = LayerGateScaffoldMemo()
    private var layerGateLevelIdentity: Any? = null
    private var layerGateContentIdentity: Any? = null
    private var layerGateTransformRevision: Long = 0L
    private var layerGateSessionInitialized: Boolean = false
    private var layerGateUpdated: Boolean = false
    private var layerGateY: Int? = null
    private var layerGatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT
    private var acceptedForLayerGate: Int = 0
    private var previousLayerGatePlayerPosition: Vec3? = null
    // The overall plan currently being executed -- a single cell
    // or a multi-cell chain. Persists across however many
    // ticks its cells take to place one at a time; cleared once ScaffoldPlan.nextStep
    // reports every cell already tracked (the chain is fully built and the real target
    // becomes an ordinary actionable candidate through the normal placement path once
    // its support is observed) or on any cleanup/abort trigger (cleanupScaffolds).
    private var activeScaffoldPlan: ScaffoldPlan? = null
    // The specific scaffold cell submitted but not yet settled by the
    // attempt tracker. Non-null blocks activeScaffoldPlan's next step / findScaffoldPlan
    // from running again (rate limit: at most one scaffold cell submission in flight)
    // until this tick's completion loop either promotes it into scaffoldLedger
    // (ACCEPTED) or drops it (any other outcome).
    private var pendingScaffoldStep: ScaffoldStep? = null
    // Once-per-terminal-encounter guard for
    // canAutoMoveComplete's scaffold-blocker resolution. True from the tick a
    // retry-exhausted scaffold gets its one bounded re-arm round until the very next
    // canAutoMoveComplete call resolves the encounter one way or another (natural
    // clear, or forced cleanup) -- see scaffoldTerminalOutcome's doc for the
    // termination trace.
    private var scaffoldTerminalRearmed: Boolean = false
    // TEMP C3MOV: scaffold chain stats surfaced on the auto-move
    // terminal summary line, reset alongside the rest of the layer-gate session state.
    // placed/broken count individual scaffold CELLS (every cell of every chain, not just
    // one per target); chains counts how many adopted plans needed more than one cell;
    // maxChainLen is the longest cells.size ever adopted (0 if no chain was ever needed).
    private var scaffoldCellsPlaced: Int = 0
    private var scaffoldCellsBroken: Int = 0
    private var scaffoldChainsUsed: Int = 0
    private var scaffoldMaxChainLength: Int = 0
    // Consecutive ticks activeScaffoldPlan has gone without landing a new
    // cell -- see the stall-abandon block at the end of tick() for why this exists
    // (nothing routes the player toward a pending scaffold cell, so a plan can stall
    // forever and, because findScaffoldPlan's only call site requires
    // !scaffoldLedger.isOutstanding(), permanently block scaffold assist for every
    // OTHER position too). Reset to 0 whenever a cell lands or the plan ends (finished
    // or abandoned); incremented once per tick activeScaffoldPlan stays non-null.
    private var scaffoldStallTicks: Int = 0

    // Flag-gated v4 plan session state (see tick()'s planFirstMode branch and
    // tickPlanMode/teardownPlanSession below). Null whenever plan mode is not the active
    // path -- flag OFF or before the first plan-mode tick ever runs.
    private var planSessionKey: PrinterSessionKey? = null
    private var planAdapter: PlanRuntimeAdapter? = null
    private var planBehaviorSettings: PlacementBehaviorSettings? = null
    // Set by invalidatePlanSession (see its own doc) whenever a manual reconcile touches the
    // world during an active plan session -- the frozen-world assumption a plan was built
    // under no longer holds, so the next tickPlanMode call must discard and rebuild instead of
    // continuing to execute against a plan that assumed a world state that has since changed.
    private var planSessionDirty: Boolean = false
    // Consecutive dirty discards (see planSessionDirty's own doc) whose own placed count was
    // truly zero -- reset to 0 by any discard that placed something, or by a session reaching
    // completion (see tickPlanAdapter's own completion branch), both of which prove the session
    // is not stuck. Read by the dirty-discard branch to arm
    // planRebuildBackoffTicksRemaining once it reaches NO_PROGRESS_BACKOFF_THRESHOLD -- an
    // infinite plan/rebuild loop at a stale support cell (retarget-divergence -> dirty -> discard
    // -> reclassify against the same cell -> divergence again) would otherwise busy-loop the
    // coordinator every single tick forever.
    private var planNoProgressCount: Int = 0
    // Ticks remaining before tickPlanMode's own no-adopted-session build path may start a new
    // coordinator cycle -- armed to NO_PROGRESS_REBUILD_BACKOFF_TICKS once planNoProgressCount
    // reaches its threshold (see the dirty-discard branch), decremented once per no-session tick
    // regardless of whether a rebuild is otherwise ready to start. 0 means no backoff active.
    private var planRebuildBackoffTicksRemaining: Int = 0
    // Once-per-backoff-episode guard for the "backing off" chat line -- true from the tick the
    // backoff first arms until planNoProgressCount is reset (progress or completion), so a
    // rebuild cycle that keeps re-arming the same stuck episode's backoff never re-announces it
    // every single time.
    private var planBackoffMessaged: Boolean = false
    // The discarded session's own identity, captured by the dirty-discard branch (the only path
    // that ever grows planNoProgressCount) at the moment of discard. Read back once the
    // no-session path is about to start a fresh build cycle (see noProgressBackoffAppliesToIdentity's
    // own doc): a streak accumulated against one identity must never throttle a genuinely
    // different one that has never itself made a no-progress attempt.
    private var planDiscardedIdentity: PlanIdentity? = null
    // Off-thread build coordinator/content-assembler for the plan-mode build phase (see
    // PlanCoordinator's own doc) -- obtained lazily via obtainPlanCoordinator/
    // obtainPlanContentAssembler, never a field initializer with a bound `::` reference: a
    // bound method reference in a field initializer runs class init on whatever thread
    // touches it first, which has crashed this mod before.
    private var planCoordinator: PlanCoordinator? = null
    private var planContentAssembler: PlanContentAssembler? = null
    // How many reservation groups the adopted session's own PrintPlan carries -- read by
    // shouldAutoRetryPlanSession (as reservationCount > 0) and by the completion chat's
    // unplaced count, without retaining the plan itself (only the count survives once the
    // session is adopted).
    private var planReservationCount: Int = 0
    // Once-per-completed-session-identity guard for the automatic replan pass (see
    // shouldAutoRetryPlanSession's own doc) -- true from the tick a retry actually fires
    // until a genuinely new session is adopted or the session is torn down. Paired with
    // planRetryIdentity: the guard is scoped to the identity the retry actually fired for,
    // so a later session built for a DIFFERENT identity (new schematic/transform/world, or
    // a manual-write replan that reset both via teardownPlanSession) is entitled to its own
    // retry even while this stays true.
    private var planRetryUsed: Boolean = false
    // The PlanIdentity (adopted session's level/content/transformRevision + behavior) the
    // retry above actually fired for -- see planRetryUsed's own doc.
    private var planRetryIdentity: PlanIdentity? = null
    // The last PlanCoordinatorStep.InProgress phase observed while no session was adopted --
    // lets tickPlanMode announce "planning..." exactly once per build episode (the tick the
    // phase first moves off IDLE) instead of on every idle tick while a cycle is building.
    private var planLastPhase: PlanCoordinatorPhase? = null
    // Once-per-completed-session guard for the plan-complete chat line (see tickPlanAdapter).
    private var planCompletionMessaged: Boolean = false
    // The (level, content, transformRevision) triple a REFUSED notice was already sent for --
    // prevents re-announcing every idle tick while the model stays REFUSED for the same
    // schematic (see tick()'s planFirstMode branch).
    private var planRefusedNoticeKey: PrinterSessionKey? = null
    // Plan-mode HUD phase, surfaced via PrinterStatus.planPhase (see PrinterHudFormatter's own
    // "Plan: ..." line) -- one of "planning"/"replanning"/"executing"/"complete"/"failed"/
    // "refused", set at each of those transitions across tickPlanMode/tickPlanAdapter/tick()'s
    // own REFUSED branch. Reset to null in teardownPlanSession; v3 never touches it (that path's
    // own PrinterStatus constructions never pass a planPhase).
    private var planPhase: String? = null
    // Caches tickPlanMode's own bounds predicate across the (possibly many-tick) span of one
    // coordinator build cycle -- schematicWorldBoundingBox is an O(content-size) scan, and
    // PlanCoordinator's own caller-purity contract already requires bounds to stay a pure
    // function of identity, so recomputing it on every tick of a multi-tick build would
    // reintroduce exactly the per-tick full-content scan this async build exists to avoid.
    private var planBoundsIdentity: PlanIdentity? = null
    private var planBoundsPredicate: ((BlockPos) -> Boolean)? = null
    // Edge-detects settings.planFirstMode turning ON while the printer stays enabled
    // (see tick()'s own ON-edge quiesce, right where settings is first read) -- a flag
    // flip mid-run is not itself a toggle-off, so it would otherwise never run the v3
    // cleanup that stray in-flight attempts/scaffold material need before a plan
    // session starts executing against the same world.
    private var previousPlanFirstMode: Boolean = false

    internal val enabled: Boolean
        get() = activation.enabled

    internal fun currentLayerGateY(): Int? = layerGateY

    // Called from Nuchematica's own manual-reconcile handling (a player's break/place inside
    // the print range settling outside the plan session's own submit/observe path) -- marks
    // the active plan session's frozen-world assumption stale so the next tickPlanMode call
    // discards it and reclassifies against the world as it now stands, rather than continuing
    // to execute a plan built for a world that has since changed underneath it. Gated by
    // isWithinBounds (see PlanRuntimeAdapter's own doc): a reconcile at a position this session
    // never planned against in the first place has nothing stale to invalidate. A no-op call
    // (flag OFF, or no session ever started) never touches anything: there is no session and no
    // in-progress build for a manual write to have any bearing on. While a session is still being
    // built instead (planAdapter null, a coordinator cycle in progress or about to start), the
    // write-revision machinery already owns reclassifying the build itself
    // (PrintWorldModel.writeRevision(), checked by the coordinator before ever adopting a
    // snapshot as Ready) -- but a manual write landing in that window is still its own new
    // episode, entitled to its own automatic replan pass the same way an adopted session's own
    // dirty-discard-rebuild already is, so the once-per-session retry latch is reset here rather
    // than left to silently suppress the upcoming session's own retry (see planRetryUsed's own
    // doc).
    internal fun invalidatePlanSession(worldPos: BlockPos): Unit {
        val session = planAdapter
        if (session != null) {
            if (session.isWithinBounds(worldPos)) {
                planSessionDirty = true
                LogUtils.getLogger().info("[nuchematica] plan session invalidated by manual reconcile at {}", worldPos)
            }
            return
        }
        // No cached bounds predicate yet (the in-progress cycle has not reached that point, or
        // none has started) means there is nothing to gate against -- reset conservatively rather
        // than risk suppressing a real replan for a write this cycle will end up caring about.
        if (planBoundsPredicate?.invoke(worldPos) != false) {
            planRetryUsed = false
            planRetryIdentity = null
            // Plan mode has no session/build to care about this reset at all when the flag is
            // off and no coordinator cycle is actually active -- every v3-only manual reconcile
            // would otherwise log this every single time. Gated on the CURRENT flag (never
            // previousPlanFirstMode, a stale snapshot from the last tick this handler is not
            // itself synchronized with) and/or an actually-active build (planLastPhase is only
            // ever non-null while the coordinator is genuinely SNAPSHOTTING/PLANNING) -- a
            // coordinator instance merely existing (obtainPlanCoordinator caches it for the rest
            // of this printer's life, cancelled or not) must not count on its own.
            val activelyBuilding = planLastPhase != null
            if (PrinterSettingsHolder.printerSettings.planFirstMode || activelyBuilding) {
                LogUtils.getLogger().info("[nuchematica] plan retry budget reset by manual reconcile at {}", worldPos)
            }
        }
    }

    // Lets MoverCore tell an ASCENT-phase gate drain (below-
    // gate stragglers wait for RECOVERY) apart from a RECOVERY-phase one (the
    // batch/discrete fallback works unrestricted, as it always has).
    internal fun currentLayerGatePhase(): PrinterLayerGatePhase = layerGatePhase

    // TEMP C3MOV: surfaced on SchematicMover's terminal summary line
    // as scaffold[placed=N broken=N chains=N maxLen=N].
    internal fun scaffoldTelemetry(): PrinterScaffoldTelemetry = PrinterScaffoldTelemetry(
        placed = scaffoldCellsPlaced,
        broken = scaffoldCellsBroken,
        chains = scaffoldChainsUsed,
        maxChainLength = scaffoldMaxChainLength,
    )

    internal fun latestFeedSnapshot(sessionKey: PrinterSessionKey): FeedSnapshot? {
        return feedSnapshotStore.latest(sessionKey)
    }

    internal fun freshFeedSnapshot(
        sessionKey: PrinterSessionKey,
        queueRevision: Long,
    ): FeedSnapshot? {
        return feedSnapshotStore.fresh(sessionKey, queueRevision)
    }

    // Placement submission registers the tracker before SchematicPrinter.tick yields,
    // and terminal results are applied before it yields as well, so no ownership gap
    // is externally observable by the later client-tick reconcile. Also queries the active
    // plan-mode session's own in-flight submissions (see PlanRuntimeAdapter.isPlacementInFlight)
    // -- its useItemOn click fires the same PlayerInteractEvent.RightClickBlock a manual player
    // click would, so without this a plan-mode placement would reconcile as "manual" and
    // invalidate the very session that just placed it.
    internal fun ownsPendingPlacement(worldPos: BlockPos): Boolean {
        return runtime.attemptTracker.isInFlight(worldPos) || planAdapter?.isPlacementInFlight(worldPos) == true
    }

    // Mirrors ownsPendingPlacement's own null-safe shape: null whenever no session is adopted
    // yet (flag OFF, or a coordinator build/rebuild still in progress) -- there is no frontier to
    // report. Read by a later mover to steer the player toward the plan's currently blocked
    // positions.
    internal fun planFrontierSnapshot(): PlanFrontierSnapshot? {
        return planAdapter?.frontierSnapshot()
    }

    // True exactly once the plan-complete chat latch (planCompletionMessaged, see
    // tickPlanAdapter's own doc) has fired for the current adopted session -- the same single
    // source of truth tickPlanAdapter itself already uses, never a second, independently-tracked
    // flag. Also requires planAdapter to still be the adopted session (defense in depth): the
    // dirty-discard branch in tickPlanMode already resets planCompletionMessaged back to false in
    // the same step it drops planAdapter, so this second check can only ever matter if some future
    // discard path forgets that reset -- it must never report a completed session as final once
    // that session is no longer the one actually being executed.
    internal fun isPlanSessionFinal(): Boolean {
        return planCompletionMessaged && planAdapter != null
    }

    internal fun isDeferredWorldPos(worldPos: BlockPos, queueRevision: Long): Boolean {
        if (Minecraft.getInstance().level == null) return false
        return deferralLedger.isDeferred(worldPos, PrintWorldModel::stateAt, queueRevision)
    }

    internal fun clearMoverCauses(): Unit {
        deferralLedger.clearMoverCauses()
    }

    internal fun grantFinalSweepBackoffBypass(): Unit {
        deferralLedger.grantFinalSweepBackoffBypass()
    }

    // Candidate abandonment itself does not defer blocks. These entry points are called
    // only after every route target is unusable or bounded no-progress retry is spent.
    internal fun deferUnreachable(positions: Set<BlockPos>): Unit {
        deferMoverPositions(positions, PrinterDeferralReason.MOVER_UNREACHABLE)
    }

    internal fun deferNoProgress(positions: Set<BlockPos>): Unit {
        deferMoverPositions(positions, PrinterDeferralReason.NO_PROGRESS)
    }

    private fun deferMoverPositions(
        positions: Set<BlockPos>,
        reason: PrinterDeferralReason,
    ): Unit {
        if (Minecraft.getInstance().level == null) return
        val queueRevision = MissingBlockHolder.missingSnapshot().revision
        for (worldPos in positions) {
            deferralLedger.defer(worldPos, reason, PrintWorldModel::stateAt, queueRevision)
        }
    }

    internal var latestStatus: PrinterStatus? = null
        private set

    internal fun toggleRequested(isCreative: Boolean): PrinterActivationEvent {
        val event = activation.toggleRequested(isCreative)
        if (event == PrinterActivationEvent.ENABLED) {
            statusTracker.reset()
            resetLayerGateSession()
            deferralLedger.clearAll()
            feedSnapshotStore.clear()
        } else {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            // Printer toggle-off is one of the mandatory scaffold
            // cleanup triggers -- never leave the scaffold material in the world.
            cleanupScaffolds()
            // Same mandatory cleanup for the plan-mode session, if one is active (see
            // teardownPlanSession's own doc) -- toggle-off is a discard trigger tick() itself
            // would never see, since a disabled printer's tick() returns before tickPlanMode.
            teardownPlanSession()
        }
        return event
    }

    internal fun tick(): Unit {
        if (!enabled) {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            return
        }

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        // World-null tick (world unloaded/not yet loaded) is a plan-mode session discard
        // trigger of its own (see teardownPlanSession's own doc) -- sweepOutstandingScaffolds
        // already skips the actual sweep with no level to destroy against, so this only ever
        // drops the stale session/adapter references, never attempts a live-world mutation.
        if (level == null) teardownPlanSession()
        val isCreative = level != null && player != null && gameMode?.playerMode?.isCreative == true
        if (activation.tick(isCreative) == PrinterActivationEvent.AUTO_DISABLED) {
            ChatSender.send("[nuchematica] printer disabled (left creative mode)")
            // Leaving creative auto-disables the printer -- attempt
            // cleanup here too (best effort; if it fails because creative is gone,
            // cleanupScaffolds reports the leftover instead of leaving it unreported).
            cleanupScaffolds()
            // Same mandatory discard for an active plan-mode session -- this auto-disable path
            // is a discard trigger tick() itself would otherwise never see, since a disabled
            // printer's tick() returns just below before tickPlanMode ever runs again.
            teardownPlanSession()
        }
        if (!enabled || level == null || player == null || gameMode == null) {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            return
        }
        // While PrintWorldModel is still building its snapshot,
        // placement stays idle -- the missing scan this tick's candidates would be
        // selected from (MissingBlockHolder.initialize) has not consumed the capture yet
        // either, so there is nothing correct to place against. Leaves latestStatus as its
        // last known value (the chat progress messages are the visible "still working"
        // signal) rather than clearing it like the disabled/no-world branches above.
        // MissingBlockHolder's own budgeted classification pass is
        // a second, later window of the same "world model not ready yet" period (it only
        // ever starts once PrintWorldModel has settled) -- placement stays idle through
        // that window too, or a mid-session re-classification (e.g. a transform change
        // while auto-move is active) would have the printer act on the STALE missing set
        // still exposed by missingSnapshot() until the pass swaps in its result.
        if (
            PrintWorldModel.status() == PrintWorldModel.Status.CAPTURING ||
            MissingBlockHolder.isInitializing()
        ) {
            return
        }

        val settings = PrinterSettingsHolder.printerSettings
        // ON-edge quiesce: settings.planFirstMode flipping from off to on while the
        // printer stays enabled is not a toggle-off (that path's own cleanup, above,
        // never runs for it) and not itself a discard trigger for a plan session (there
        // is not one yet) -- so run the same v3 in-flight/scaffold cleanup the
        // toggle-off path performs, once, right here, before any plan-mode tick can
        // execute against a world v3 might still be mid-placement in.
        if (settings.planFirstMode && !previousPlanFirstMode) {
            runtime.cancelAll()
            cleanupScaffolds()
        }
        previousPlanFirstMode = settings.planFirstMode
        if (settings.planFirstMode && PrintWorldModel.status() == PrintWorldModel.Status.READY) {
            tickPlanMode(level, player, gameMode, settings)
            return
        }
        // planFirstMode ON but the frozen-world model is not READY yet -- IDLE (capture never
        // started for this identity) or REFUSED for this schematic; CAPTURING itself never
        // reaches this branch, since the idle-return guard above (status() == CAPTURING) already
        // intercepts every tick while capture is still in progress, before planFirstMode is even
        // read. There is nothing correct to plan against here either, but this is still plan
        // mode -- never fall through into v3's own selector/gate/ScaffoldLedger machinery
        // underneath it, which would place against a live world the plan session never accounted
        // for. Idle instead; the branch above resumes plan mode the moment the model reports
        // READY. An already-built session is left exactly as it was UNLESS it no longer applies
        // to the world as it now stands (see the discard check just below) -- otherwise it would
        // sit there stale for however long the model stays off READY.
        if (settings.planFirstMode) {
            // A session built for an identity (level/content/transform) this tick no longer
            // matches, or a model that has settled REFUSED for this schematic (a status this
            // session can never recover into READY from), has nothing correct left to resume
            // into once READY does return -- discard it now rather than let it sit stale for
            // however long the model stays off READY. teardownPlanSession's own sweep already
            // no-ops unless the session's captured level is still the live one.
            val currentIdentity = PrinterSessionKey(
                level,
                SchematicHolder.renderingBlocks,
                SchematicRenderManager.currentTransformRevision(),
            )
            val session = planSessionKey
            if (
                session != null &&
                (!session.matches(currentIdentity) || PrintWorldModel.status() == PrintWorldModel.Status.REFUSED)
            ) {
                teardownPlanSession()
            }
            // The model being IDLE/REFUSED here means no in-progress build cycle can ever reach
            // an adoptable Ready step -- cancel unconditionally so a cycle left mid-build (no
            // session adopted yet, so teardownPlanSession above never ran) does not keep running
            // against a model that just left READY. Idempotent, and never creates a coordinator
            // that does not already exist.
            planCoordinator?.cancel()
            // REFUSED is a standing idle state for this schematic (never falls through to v3),
            // so the notice must fire once per (level, content, transform) triple rather than
            // every idle tick for however long the flag stays on.
            if (
                PrintWorldModel.status() == PrintWorldModel.Status.REFUSED &&
                planRefusedNoticeKey?.matches(currentIdentity) != true
            ) {
                ChatSender.send("[nuchematica] schematic too large for plan mode; printer idle")
                planRefusedNoticeKey = currentIdentity
                planPhase = "refused"
                // REFUSED never reaches tickPlanMode's own dirty-discard/completion reset paths
                // (this schematic can never adopt a session to discard or complete in the first
                // place) -- reset the streak directly here instead, once per newly-REFUSED
                // identity, so it never sits stale to throttle a later, different identity's
                // first build.
                planNoProgressCount = 0
                planRebuildBackoffTicksRemaining = 0
                planBackoffMessaged = false
                // Written once here, when the notice fires, rather than every idle REFUSED tick:
                // nothing else touches latestStatus for the rest of this planFirstMode branch (it
                // returns right after, see below), so this single write already stays the HUD's
                // value for as long as the schematic stays REFUSED.
                val previousStatus = latestStatus
                latestStatus = PrinterStatus(
                    remaining = previousStatus?.remaining ?: 0,
                    placed = previousStatus?.placed ?: 0,
                    skips = emptyMap(),
                    planPhase = planPhase,
                )
            }
            // Plan mode must never leave a consumable v3 feed while idle here, regardless of
            // whether the model is IDLE or REFUSED for this identity.
            feedSnapshotStore.clear()
        }
        if (settings.planFirstMode) return
        teardownPlanSession()
        runtime.rateLimiter.updateAttemptsPerTick(settings.attemptsPerTick)
        runtime.rateLimiter.updateIntervalTicks(settings.placementIntervalTicks)
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        synchronizeLayerGateSession(level, content, transformRevision)
        val playerPosition = player.position()
        val playerMoved = previousLayerGatePlayerPosition?.let { previousPosition ->
            playerPosition.distanceToSqr(previousPosition) > PLAYER_MOVEMENT_DISTANCE_SQUARED
        } ?: false
        previousLayerGatePlayerPosition = playerPosition
        layerGateMissingCache.synchronize(
            contentIdentity = content,
            expectedStates = content.blocks,
            transformRevision = transformRevision,
            substituteLookalikes = settings.substituteLookalikes,
        )
        layerGateSupportMemo.synchronize(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        layerGateScaffoldMemo.synchronize(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        deferralLedger.synchronize(
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        val reach = minOf(settings.reach, gameMode.pickRange.toDouble())
        val eyePosition = player.eyePosition
        // Incremental sync -- an ordinary placement only bumps
        // missing.revision (contentIdentity/transformRevision/substituteLookalikes stay
        // put), which applies the exact MissingBlockHolder.changesSince delta instead of
        // rescanning every missing position; only a genuine structural change (reload,
        // transform, or this setting) does the O(all-missing) rebuild.
        val eligibleMissing = layerGateEligibleMissingCache.sync(
            contentIdentity = content,
            transformRevision = transformRevision,
            substituteLookalikes = settings.substituteLookalikes,
            missingRevision = missing.revision,
            missingLocal = missing.missingLocal,
            changesSince = MissingBlockHolder::changesSince,
        )
        // A structural change (fresh printer-on for a just-loaded large
        // schematic is the common case: contentIdentity starts null, so this is always
        // true the first tick) can span several ticks of budgeted work -- the sync()
        // call above already advanced it as far as this tick's budget allows, but the
        // eligibleMissing/gate/placement machinery below all assume a COMPLETE,
        // consistent view (see isRebuilding's doc for why a stale-but-untorn snapshot is
        // not good enough to act on), so placement stays idle for the rest of this
        // rebuild exactly like the PrintWorldModel/MissingBlockHolder gate above.
        if (layerGateEligibleMissingCache.isRebuilding()) return
        // Hoisted so the gate's isLayerSupported/inReach
        // calculations below and findScaffoldPlan later in this tick all share the
        // exact same "is worldPos part of the schematic" test instead of each
        // re-deriving it.
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            content.blocks.containsKey(SchematicRenderManager.worldBlockToLocal(pos))
        }
        val canScaffold: (BlockPos, BlockState, (BlockPos) -> BlockState) -> Boolean = { pos, expectedState, stateAtFn ->
            layerGateScaffoldMemo.canScaffold(pos, expectedState, stateAtFn, isSchematicPosition, playerPosition)
        }
        val isLayerSupported: (Int) -> Boolean = { y ->
            eligibleMissing.byY[y]?.any { entry ->
                // Gate support/inReach classification reads the
                // frozen-world model instead of a live level read.
                isActionableMissing(
                    entry.worldPos,
                    entry.expectedState,
                    PrintWorldModel::stateAt,
                    hasSupport = layerGateSupportMemo::hasSupport,
                    canScaffold = canScaffold,
                ) && !deferralLedger.isDeferred(entry.worldPos, PrintWorldModel::stateAt, missing.revision)
            } == true
        }
        val gateYBeforeUpdate = if (layerGateUpdated) {
            layerGateY
        } else {
            eligibleMissing.sortedDistinctYs.firstOrNull(isLayerSupported)
        }
        val gateReach = reach + GATE_REACH_MARGIN
        val gateReachSquared = gateReach * gateReach
        var inReachAboveGate = 0
        var inReachEligibleAtOrBelowGate = 0
        if (gateYBeforeUpdate != null) {
            // A naive full scan would walk the ENTIRE missing.missingLocal list every
            // tick (both counts only bypass stall tracking below, per the doc a few
            // lines down -- but the scan itself would run unconditionally once ANY layer
            // was supported, not just the first tick). At 0_all's ~3.5M-entry missing set
            // that is not a one-time hitch but a permanent per-tick cost. Both counts
            // only ever accept an entry whose distanceSquared <= gateReachSquared;
            // a position's Y-layer center alone already lower-bounds its 3D distance
            // from eyePosition, so any Y outside [yLow, yHigh] below cannot possibly
            // pass that check regardless of X/Z -- restricting the scan to that Y band
            // is an EXACT bound, not an approximation, and turns an O(all-missing) scan
            // into one bounded by gateReach (a small, fixed constant) times the local
            // per-layer density, not by the schematic's total size.
            // The one real behavior difference from a naive full scan: inReachAboveGate
            // sources from eligibleMissing.byY (ELIGIBLE entries only), whereas a full
            // scan would also count CATEGORY_EXCLUDED positions above the gate (its own
            // doc: "not filtered by isActionableMissing"). Excluded categories are a
            // small, bounded fraction of any schematic (signs/doors/beds/... ~33 @Fantasy)
            // and this count only ever feeds the coarse "is there other
            // work nearby" stall-escape signal below, so undercounting them by a
            // handful is an accepted, documented trade-off. inReachEligibleAtOrBelowGate
            // is exact regardless: isActionableMissing already requires
            // eligibility, so eligibleMissing.byY was always its true source set.
            val yLow = kotlin.math.floor(eyePosition.y - gateReach).toInt()
            val yHigh = kotlin.math.ceil(eyePosition.y + gateReach).toInt()
            for (y in yLow..yHigh) {
                val bucket = eligibleMissing.byY[y] ?: continue
                for (entry in bucket) {
                    // Coarse center-distance prefilter, shared by both counts below:
                    // the widest an envelope hit point (potentialSupportHitPoints) can
                    // sit from this block's own center is well under GATE_REACH_MARGIN,
                    // so this can never exclude a position the precise envelope check
                    // further below would otherwise admit.
                    val centerX = entry.worldPos.x + 0.5
                    val centerY = entry.worldPos.y + 0.5
                    val centerZ = entry.worldPos.z + 0.5
                    val deltaX = eyePosition.x - centerX
                    val deltaY = eyePosition.y - centerY
                    val deltaZ = eyePosition.z - centerZ
                    val distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    if (distanceSquared <= gateReachSquared) {
                        // inReachAboveGate stays center-distance-based.
                        // It is not filtered by isActionableMissing (any missing
                        // position above the gate counts, supported or not) and only
                        // bypasses stall tracking below as a coarse "is there other
                        // work nearby" signal -- a hit envelope has no principled
                        // meaning for a position that may have no real support at all,
                        // and this count does not drive actual coverage/placement
                        // decisions the way inReachEligibleAtOrBelowGate does, so it is
                        // left as center-distance-based rather than switched
                        // speculatively.
                        if (entry.worldPos.y > gateYBeforeUpdate) inReachAboveGate++
                        // inReachEligibleAtOrBelowGate DOES switch to the
                        // selector-aligned hit envelope: it is exactly the
                        // actionable/placeable-filtered count that drives the stall
                        // escape below, the same class of "planner/gate believes this
                        // is in reach but the selector's real hit points are not"
                        // mismatch that motivated the switch.
                        if (
                            entry.worldPos.y <= gateYBeforeUpdate &&
                            isActionableMissing(
                                entry.worldPos,
                                entry.expectedState,
                                PrintWorldModel::stateAt,
                                hasSupport = layerGateSupportMemo::hasSupport,
                                canScaffold = canScaffold,
                            ) &&
                            !deferralLedger.isDeferred(entry.worldPos, PrintWorldModel::stateAt, missing.revision) &&
                            potentialSupportHitPoints(entry.worldPos, entry.expectedState).any { point ->
                                eyePosition.distanceToSqr(point) <= gateReachSquared
                            }
                        ) {
                            inReachEligibleAtOrBelowGate++
                        }
                    }
                }
            }
        }
        val gateYForTick = layerGate.update(
            eligibleYs = eligibleMissing.sortedDistinctYs,
            isLayerSupported = isLayerSupported,
            inReachAboveGate = inReachAboveGate,
            inReachEligibleAtOrBelowGate = inReachEligibleAtOrBelowGate,
            acceptedThisTick = acceptedForLayerGate,
            playerMoved = playerMoved,
            onFloorYEscalated = { floorY ->
                LogUtils.getLogger().info("C3DBG[gate] floorY escalated to {}", floorY)
            },
        )
        layerGateY = gateYForTick
        layerGatePhase = layerGate.currentPhase()
        layerGateUpdated = true
        val gatedMissingLocal = if (gateYForTick == null) {
            missing.missingLocal
        } else {
            missing.missingLocal.filter { localPos ->
                val entry = layerGateMissingCache[localPos]
                entry == null || entry.worldPos.y <= gateYForTick
            }
        }
        // Scaffold assist joins EVERY phase's plan (ASCENT
        // lanes included), not just RECOVERY/the final sweep -- the RECOVERY-only MVP
        // gate is removed. A plan may be a multi-cell chain, not
        // just a single cell -- activeScaffoldPlan tracks the plan currently being
        // executed across however many ticks its cells take to place one at a time,
        // resolving its next unplaced cell (nextStep) every tick until the whole chain
        // is built. Rate-limited to one scaffold CELL submission in flight: a fresh plan
        // is only searched for while neither a submission, an active plan, nor a ledger
        // entry already exists.
        var scaffoldStepForTick: ScaffoldStep? = null
        var scaffoldCandidateForTick: PrinterCandidate? = null
        if (pendingScaffoldStep == null) {
            val currentPlan = activeScaffoldPlan
            val step = if (currentPlan != null) {
                val next = currentPlan.nextStep(scaffoldLedger::isScaffoldCell)
                if (next == null) {
                    // Every cell is already tracked by the ledger; the real target
                    // becomes an ordinary actionable candidate through the normal
                    // placement path below once its support is observed -- nothing
                    // further to submit for this plan.
                    activeScaffoldPlan = null
                    null
                } else {
                    next
                }
            } else if (!scaffoldLedger.isOutstanding()) {
                val plan = findScaffoldPlan(
                    gatedMissingLocal = gatedMissingLocal,
                    content = content,
                    missingRevision = missing.revision,
                    eyePosition = eyePosition,
                    reach = reach,
                    playerFeetPos = playerPosition,
                    isSchematicPosition = isSchematicPosition,
                )
                if (plan != null) {
                    activeScaffoldPlan = plan
                    if (plan.cells.size > 1) scaffoldChainsUsed++
                    if (plan.cells.size > scaffoldMaxChainLength) scaffoldMaxChainLength = plan.cells.size
                    plan.nextStep(scaffoldLedger::isScaffoldCell)
                } else {
                    null
                }
            } else {
                null
            }
            if (step != null) {
                scaffoldStepForTick = step
                // Resolves the scaffold's own hit/rotation by reusing the ordinary
                // selector for a single synthetic position -- "as a normal ... placement
                // attempt with prediction gate" instead of a parallel
                // hit-finding algorithm.
                scaffoldCandidateForTick = runtime.candidateSelector.select(
                    missingLocal = listOf(SchematicRenderManager.worldBlockToLocal(step.scaffoldPos)),
                    expectedStateAt = { SCAFFOLD_BLOCK_STATE },
                    localToWorld = { step.scaffoldPos },
                    stateAt = level::getBlockState,
                    placementContext = { expectedState, hit ->
                        BlockPlaceContext(
                            level,
                            player,
                            InteractionHand.MAIN_HAND,
                            ItemStack(expectedState.block.asItem()),
                            hit,
                        )
                    },
                    eyePosition = eyePosition,
                    reach = reach,
                    playerFeetPos = playerPosition,
                ).firstOrNull()
            }
        }
        val sessionKey = PrinterSessionKey(
            level = level,
            content = content,
            transformRevision = transformRevision,
        )
        val gateway = FacePlacementGateway(
            delegate = RealPlacementGateway(gameMode, player, level),
            facePlacement = settings.facePlacement,
            eyePosition = eyePosition,
            setCameraTarget = cameraEase::setTarget,
        )
        val completed = runtime.tick(
            PrinterTickContext(
                tick = level.gameTime,
                queueRevision = missing.revision,
                sessionKey = sessionKey,
                missingLocal = gatedMissingLocal,
                expectedStateAt = content.blocks::get,
                localToWorld = SchematicRenderManager::localBlockToWorld,
                stateAt = level::getBlockState,
                placementContext = { expectedState, hit ->
                    BlockPlaceContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        ItemStack(expectedState.block.asItem()),
                        hit,
                    )
                },
                eyePosition = eyePosition,
                reach = reach,
                itemSupplier = CreativeItemSupplier(player, gameMode),
                placementGateway = gateway,
                playerFeetPos = playerPosition,
                scaffoldCandidate = scaffoldCandidateForTick,
            ),
        )
        // The candidate was appended to this tick's submission loop; if it actually
        // got submitted (attemptTracker now tracks it), remember the pairing so the
        // completion loop below can route its own outcome away from
        // MissingBlockHolder once it settles (possibly several ticks later).
        if (
            scaffoldCandidateForTick != null && scaffoldStepForTick != null &&
            runtime.attemptTracker.isInFlight(scaffoldCandidateForTick.worldPos)
        ) {
            pendingScaffoldStep = scaffoldStepForTick
        }
        runtime.feedSnapshot()?.let { snapshot ->
            feedSnapshotStore.update(sessionKey, snapshot)
        } ?: feedSnapshotStore.clear()
        completed.forEach { result ->
            // The generic click reconcile must never become a second progress owner
            // after this terminal result releases the attempt tracker.
            ClientBlockInteractHandler.pendingPlacePositions.remove(result.attempt.worldPos)
            val scaffoldStep = pendingScaffoldStep
            if (scaffoldStep != null && result.attempt.worldPos == scaffoldStep.scaffoldPos) {
                // This is the scaffold cell's OWN placement outcome,
                // not a schematic position -- it must never enter MissingBlockHolder
                // bookkeeping. Only ACCEPTED promotes it into the printer-owned
                // ledger; any other outcome just frees the rate limit so a later tick
                // may retarget the same or a different scaffold cell.
                if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                    scaffoldLedger.recordPlaced(scaffoldStep.scaffoldPos, scaffoldStep.targetPos)
                    scaffoldCellsPlaced++
                    // A cell just landed -- this plan is making real
                    // progress, so its stall clock restarts.
                    scaffoldStallTicks = 0
                    // Write-on-ack -- the scaffold cell's own
                    // placement just settled (observedState is the ack's own read, no
                    // extra world access needed), and it sits inside the model's region
                    // (a neighbor of a schematic position) so downstream support/
                    // isPlaceable reads see it as occupied immediately, not just once it
                    // is broken back to air.
                    PrintWorldModel.recordWrite(scaffoldStep.scaffoldPos, result.observedState)
                }
                pendingScaffoldStep = null
                return@forEach
            }
            when (result.outcome) {
                PrinterAttemptOutcome.ACCEPTED,
                PrinterAttemptOutcome.WRONG_STATE,
                -> {
                    val observedState = level.getBlockState(result.attempt.worldPos)
                    MissingBlockHolder.placed(
                        result.attempt.worldPos,
                        observedState,
                    )?.let(SchematicRenderManager::onMissingBlockChange)
                    // Write-on-ack -- stores the state the server
                    // just confirmed (read once above, no extra world access needed).
                    PrintWorldModel.recordWrite(result.attempt.worldPos, observedState)
                    // No-op unless worldPos is the dependent real block of a tracked
                    // scaffold: promotes PLACED -> CONSUMED so
                    // tickBreaks below issues the break this same tick.
                    scaffoldLedger.markConsumed(result.attempt.worldPos)
                }
                PrinterAttemptOutcome.REJECTED -> Unit
                // A timeout can leave a block client-visible. The next full missing
                // rescan recovers that edge; a timeout itself must not claim progress.
                PrinterAttemptOutcome.TIMEOUT -> Unit
            }
        }
        // Drives the break ack cycle unconditionally (not gated by scaffoldAssistActive):
        // once a scaffold is CONSUMED it must be broken regardless of the current gate
        // phase ("never leave the scaffold material in the world" invariant).
        scaffoldLedger.tickBreaks(
            tick = level.gameTime,
            stateAt = level::getBlockState,
            destroy = gameMode::destroyBlock,
            onBroken = { scaffoldPos ->
                // Write-on-ack -- a confirmed scaffold break
                // stores air at that position (the ack timeline itself,
                // PrinterAttemptTracker.observe inside tickBreaks, stays on a live read;
                // only the resulting write goes through the model).
                PrintWorldModel.recordWrite(scaffoldPos, Blocks.AIR.defaultBlockState())
                scaffoldCellsBroken++
                // Cascades the break target-end-first through a
                // chain. A no-op unless scaffoldPos was itself a PRIOR chain cell's own
                // step-target (ScaffoldPlan.nextStep wires each cell's targetPos to the
                // next one down the chain) -- in that case this releases the
                // predecessor cell to CONSUMED, exactly the same markConsumed path an
                // ordinary real-block placement uses for the single-cell case, so the
                // whole chain unwinds one confirmed break at a time, target end first,
                // with zero new ledger machinery.
                scaffoldLedger.markConsumed(scaffoldPos)
            },
            onRetryExhausted = { scaffoldPos, attempts ->
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] break retry exhausted pos={} attempts={}", scaffoldPos, attempts,
                )
            },
        )
        // Nothing routes the player TOWARD a pending scaffold cell -- a plan's next
        // cell only ever gets submitted opportunistically, whenever the ordinary
        // mover/lane sweep happens to already be in reach. A plan whose remaining
        // cells never come back into reach would therefore stall forever, and because
        // the findScaffoldPlan call site above requires
        // !scaffoldLedger.isOutstanding() -- one global flag, not per-target -- a
        // single stalled plan would permanently block scaffold assist for every OTHER
        // position for the rest of the active build.
        // After SCAFFOLD_STALL_ABANDON_TICKS with no new cell landing,
        // abandon the plan -- cleanupScaffolds tears down whatever cells did land,
        // exactly like any other abort path -- and defer its target as NO_PROGRESS so
        // findScaffoldPlan does not immediately re-select the same unreachable position
        // next tick (it gets another chance once backoff elapses, same as any other
        // NO_PROGRESS deferral), freeing the ledger for a DIFFERENT, hopefully
        // reachable, position in the meantime.
        if (activeScaffoldPlan != null) {
            scaffoldStallTicks++
            if (scaffoldStallTicks >= SCAFFOLD_STALL_ABANDON_TICKS) {
                val stalledTarget = activeScaffoldPlan?.targetPos
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] plan abandoned after {} stall ticks target={}",
                    scaffoldStallTicks,
                    stalledTarget,
                )
                cleanupScaffolds()
                if (stalledTarget != null) {
                    deferralLedger.defer(
                        stalledTarget,
                        PrinterDeferralReason.NO_PROGRESS,
                        PrintWorldModel::stateAt,
                        missing.revision,
                    )
                }
                scaffoldStallTicks = 0
            }
        } else {
            scaffoldStallTicks = 0
        }
        val acceptedThisTick = completed.count { result ->
            result.outcome == PrinterAttemptOutcome.ACCEPTED
        }
        acceptedForLayerGate = acceptedThisTick
        latestStatus = statusTracker.update(
            levelIdentity = level,
            contentIdentity = content,
            transformRevision = transformRevision,
            remaining = missing.missingLocal.size,
            acceptedDelta = acceptedThisTick,
            skips = runtime.skipLog.snapshot(),
        )
        if (settings.facePlacement) {
            val (easedYaw, easedPitch) = cameraEase.ease(player.yRot, player.xRot)
            player.setYRot(easedYaw)
            player.setXRot(easedPitch)
        }
    }

    // Flag-gated v4 execution path (see tick()'s planFirstMode branch above): drives the
    // off-thread PlanCoordinator build (see its own doc) to a Ready PlanRuntimeAdapter session,
    // then ticks that adapter exactly once. Runs entirely apart from the v3 selector/gate/
    // deferral/ScaffoldLedger/feedSnapshot machinery in tick() above -- none of those are read
    // or written from here, and this function is this session's only entry point into
    // PlanCoordinator/PlanRuntimeAdapter/PlanExecutionCursor.
    private fun tickPlanMode(
        level: ClientLevel,
        player: LocalPlayer,
        gameMode: MultiPlayerGameMode,
        settings: PrinterSettings,
    ): Unit {
        // Plan mode must never expose a stale v3 feed during ANY plan-mode tick, including
        // build/idle ticks -- cleared unconditionally, first, rather than only once an adapter
        // actually ticks like the old synchronous build did.
        feedSnapshotStore.clear()

        // A manual reconcile touched the print range since the last tick (see
        // invalidatePlanSession's own doc) -- the active session's frozen-world assumption no
        // longer holds. Sweep its own outstanding scaffold material, cancel whatever build
        // cycle might be in progress too, then discard the session outright so the identity
        // check below rebuilds a fresh plan against the world as it stands on the NEXT
        // tickPlanMode call -- see the early return just below for why this same tick never
        // reaches that check itself.
        if (planSessionDirty) {
            // No-progress rebuild backoff (see planNoProgressCount's own doc): read BEFORE the
            // adapter is discarded below -- a rebuild cycle whose own discard placed nothing at
            // all is making no headway, the exact shape of an infinite plan/rebuild loop pinned
            // on a stale support cell.
            val placedAtDiscard = planAdapter?.targetActionCounts()?.placed ?: 0
            val decision = evaluateNoProgressBackoff(
                placedAtDiscard = placedAtDiscard,
                currentNoProgressCount = planNoProgressCount,
                threshold = NO_PROGRESS_BACKOFF_THRESHOLD,
            )
            planNoProgressCount = decision.noProgressCount
            if (decision.armBackoff) {
                planRebuildBackoffTicksRemaining = NO_PROGRESS_REBUILD_BACKOFF_TICKS
                if (!planBackoffMessaged) {
                    ChatSender.send("[nuchematica] plan keeps getting invalidated; backing off")
                    planBackoffMessaged = true
                }
            } else if (decision.noProgressCount == 0) {
                planRebuildBackoffTicksRemaining = 0
                planBackoffMessaged = false
            }
            // Captured while the discarded session's own key/behavior are still set (before the
            // nulling below) -- read back by the no-session cycle-start check so a genuinely
            // different identity's build is never throttled by this streak.
            planDiscardedIdentity = planSessionKey?.let { key ->
                planBehaviorSettings?.let { behavior -> PlanIdentity(key.level, key.content, key.transformRevision, behavior) }
            }
            sweepOutstandingScaffolds(planAdapter)
            planCoordinator?.cancel()
            planAdapter = null
            planSessionKey = null
            planSessionDirty = false
            // A manual-write replan is a NEW episode entitled to its own retry -- the discarded
            // session's retry (if any) must not carry over and suppress the rebuilt one's.
            planRetryUsed = false
            planRetryIdentity = null
            // A manual write after completion starts a NEW episode -- the final signal
            // (isPlanSessionFinal) must not survive into its build window, or a mover still
            // reading it would terminate while a replan is coming.
            planCompletionMessaged = false
            // Teardown only this tick -- the rebuild below must wait for the NEXT tickPlanMode
            // call rather than reclassifying against the world in the very same tick the
            // divergence was flagged in: PrintWorldModel/MissingBlockHolder have not
            // necessarily caught up with whatever manual write or retarget mismatch just set
            // this flag, so a same-tick rebuild could plan against a model that is itself
            // already stale again.
            return
        }

        // Any READY plan-mode tick (this one) means the previous REFUSED episode, if any, has
        // ended -- clear the notice key so a later return to REFUSED, even for the same
        // (level, content, transform) triple, announces again instead of staying suppressed by
        // the old key.
        planRefusedNoticeKey = null

        val content = SchematicHolder.renderingBlocks
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        val sessionKey = PrinterSessionKey(level, content, transformRevision)
        val behavior = PlacementBehaviorSettings(
            substituteLookalikes = settings.substituteLookalikes,
            placeWaterloggedDry = settings.placeWaterloggedDry,
        )

        val adapter = planAdapter
        if (adapter != null) {
            if (planSessionKey?.matches(sessionKey) == true && planBehaviorSettings == behavior) {
                tickPlanAdapter(adapter, level, player, gameMode, settings, behavior)
                return
            }
            // Identity (new schematic/transform/world) or behavior (a settings toggle the
            // planner itself must account for) changed under an already-adopted session --
            // nothing correct is left to resume; discard outright and rebuild fresh next tick.
            teardownPlanSession()
            return
        }

        // No adopted session yet -- keep driving (or start) a coordinator build cycle this tick,
        // unless the no-progress backoff (see planRebuildBackoffTicksRemaining's own doc) is
        // still counting down: idle this tick too, same convention as the IDLE phase branch
        // below (latestStatus left untouched). Identity is computed first (before that
        // early-return) so a streak captured against a DIFFERENT identity never throttles this
        // one -- see noProgressBackoffAppliesToIdentity's own doc.
        val identity = PlanIdentity(level, content, transformRevision, behavior)
        if (!noProgressBackoffAppliesToIdentity(planDiscardedIdentity, identity)) {
            planNoProgressCount = 0
            planRebuildBackoffTicksRemaining = 0
            planBackoffMessaged = false
        }
        if (planRebuildBackoffTicksRemaining > 0) {
            planRebuildBackoffTicksRemaining--
            return
        }
        val boundsPredicate = boundsPredicateFor(identity, content)
        when (val step = obtainPlanCoordinator().advance(identity, obtainPlanContentAssembler(), boundsPredicate)) {
            is PlanCoordinatorStep.Ready -> {
                val session = step.session
                val sessionBehavior = session.identity.behavior
                planAdapter = session.adapter
                planSessionKey = sessionKey
                planBehaviorSettings = sessionBehavior
                // reservedCount counts reserved CELLS; reservations.size would count trigger
                // buckets, undercounting whenever several reserved cells share one trigger.
                planReservationCount = session.plan.report.reservedCount
                planLastPhase = null
                planCompletionMessaged = false
                ChatSender.send(
                    "[nuchematica] plan ready (${session.adapter.targetActionCounts().remaining} targets)",
                )
                // Falls through and ticks the adapter this same call -- same as the old
                // synchronous build did.
                tickPlanAdapter(session.adapter, level, player, gameMode, settings, sessionBehavior)
            }
            is PlanCoordinatorStep.InProgress -> {
                val previousPhase = planLastPhase
                if (
                    (previousPhase == null || previousPhase == PlanCoordinatorPhase.IDLE) &&
                    step.phase == PlanCoordinatorPhase.SNAPSHOTTING
                ) {
                    ChatSender.send("[nuchematica] planning...")
                }
                planLastPhase = step.phase
                if (step.phase == PlanCoordinatorPhase.SNAPSHOTTING || step.phase == PlanCoordinatorPhase.PLANNING) {
                    // A manual-write replan (see invalidatePlanSession's own doc) drives this
                    // exact same build path as an ordinary first build -- planRetryUsed still
                    // true here is what tells them apart, since nothing else distinguishes a
                    // fresh identity's first cycle from the replan cycle that follows a
                    // just-fired automatic retry. Scoped to planRetryIdentity matching this
                    // build's own identity: a NEW identity's first build (different schematic/
                    // transform/world/behavior) after another identity's retry must read
                    // "planning", not "replanning" -- the retry latch it would otherwise read is
                    // not its own.
                    planPhase = if (planRetryUsed && planRetryIdentity?.matches(identity) == true) {
                        "replanning"
                    } else {
                        "planning"
                    }
                    val previousStatus = latestStatus
                    latestStatus = PrinterStatus(
                        remaining = previousStatus?.remaining ?: 0,
                        placed = previousStatus?.placed ?: 0,
                        skips = emptyMap(),
                        planPhase = planPhase,
                    )
                }
                // Otherwise (IDLE) the printer idles this tick -- latestStatus is left
                // untouched, same convention as the capture-window guard in tick() above.
            }
            PlanCoordinatorStep.Failed -> {
                // The coordinator's onPlanningFailure callback already notified once in chat;
                // advance() keeps being called every tick (cheap, via the failure latch) and
                // self-recovers once the world or identity changes. The HUD still needs its own
                // signal though (chat alone is easy to miss) -- carry the previous counts forward,
                // same shape the planning/replanning branch above uses, written every tick this
                // step recurs (a repeated identical write is harmless and simpler than a
                // once-per-episode latch here).
                // Reset so the next successful build episode's SNAPSHOTTING transition
                // announces "planning..." again instead of staying suppressed by this one's.
                planLastPhase = null
                planPhase = "failed"
                val previousStatus = latestStatus
                latestStatus = PrinterStatus(
                    remaining = previousStatus?.remaining ?: 0,
                    placed = previousStatus?.placed ?: 0,
                    skips = emptyMap(),
                    planPhase = planPhase,
                )
            }
        }
    }

    // Drives an adopted plan-mode session's adapter exactly one tick, then resolves this
    // tick's HUD status and -- once the session is actually complete -- the one-pass automatic
    // replan / completion-chat decision (see shouldAutoRetryPlanSession's own doc). Shared by
    // both tickPlanMode's already-adopted path and the tick a coordinator build first goes
    // Ready, since the latter ticks its freshly adopted adapter the same call it is adopted in.
    private fun tickPlanAdapter(
        adapter: PlanRuntimeAdapter,
        level: ClientLevel,
        player: LocalPlayer,
        gameMode: MultiPlayerGameMode,
        settings: PrinterSettings,
        behavior: PlacementBehaviorSettings,
    ): Unit {
        val eyePosition = player.eyePosition
        val reach = minOf(settings.reach, gameMode.pickRange.toDouble())
        val gateway = FacePlacementGateway(
            delegate = RealPlacementGateway(gameMode, player, level),
            facePlacement = settings.facePlacement,
            eyePosition = eyePosition,
            setCameraTarget = cameraEase::setTarget,
        )
        adapter.tick(
            PlanRuntimeTickContext(
                tick = level.gameTime,
                stateAt = PrintWorldModel::stateAt,
                liveStateAt = level::getBlockState,
                recordWrite = PrintWorldModel::recordWrite,
                eyePosition = eyePosition,
                reach = reach,
                playerFeetPos = player.position(),
                settings = behavior,
                placementContext = { expectedState, hit ->
                    BlockPlaceContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        ItemStack(expectedState.block.asItem()),
                        hit,
                    )
                },
                predictPlacement = { item, context -> item.getPlacementState(context) },
                orientedPrediction = planOrientedPrediction(behavior),
                itemSupplier = CreativeItemSupplier(player, gameMode),
                placementGateway = gateway,
                destroy = gameMode::destroyBlock,
                placementIntervalTicks = settings.placementIntervalTicks,
                attemptsPerTick = settings.attemptsPerTick,
                onFrozenLiveDivergence = { planSessionDirty = true },
                clearPendingPlace = ClientBlockInteractHandler.pendingPlacePositions::remove,
            ),
        )

        val cursorStatus = adapter.cursor.status()
        // The current session's own PlanIdentity, built the same way tickPlanMode builds one for
        // a fresh cycle (level/content/transformRevision from the adopted session's own key,
        // behavior from this same session's) -- lets the retry guard below be scoped to the
        // identity a retry actually fired for rather than to plan mode as a whole.
        val adoptedKey = planSessionKey
        val currentIdentity = adoptedKey?.let { key ->
            PlanIdentity(key.level, key.content, key.transformRevision, behavior)
        }
        val retryAlreadyUsedForThisIdentity = planRetryUsed &&
            currentIdentity != null &&
            planRetryIdentity?.matches(currentIdentity) == true
        // Computed before the two latestStatus writes below (not just before the retry's own
        // execution further down) -- shouldAutoRetryPlanSession already returns false while
        // cursorStatus is not complete, so this read is a safe no-op on every non-terminal tick,
        // and on the terminal tick it lets planPhase/latestStatus say "replanning" instead of
        // "complete" on the exact tick that is about to discard the session and retry.
        val willRetry = shouldAutoRetryPlanSession(
            cursorStatus,
            planReservationCount > 0,
            retryAlreadyUsedForThisIdentity,
        )
        planPhase = when {
            !cursorStatus.isComplete -> "executing"
            willRetry -> "replanning"
            else -> "complete"
        }
        latestStatus = PrinterStatus(
            remaining = cursorStatus.pendingCount + cursorStatus.waitingCount + cursorStatus.inFlightCount,
            placed = cursorStatus.doneCount,
            skips = emptyMap(),
            planPhase = planPhase,
        )
        // Overrides the block above with real-target-only counts (see
        // PlanRuntimeAdapter.targetActionCounts' own doc) -- the HUD reports progress on
        // schematic targets, not the scaffold material cells the player never asked to track.
        val targetCounts = adapter.targetActionCounts()
        latestStatus = PrinterStatus(
            remaining = targetCounts.remaining,
            placed = targetCounts.placed,
            skips = emptyMap(),
            planPhase = planPhase,
        )

        if (settings.facePlacement) {
            val (easedYaw, easedPitch) = cameraEase.ease(player.yRot, player.xRot)
            player.setYRot(easedYaw)
            player.setXRot(easedPitch)
        }

        if (!cursorStatus.isComplete) return
        // The adapter's own final-layer sweep re-visit (see PlanRuntimeAdapter.isSweepPending's
        // own doc) is either still actively dispatching or has not even had its own boundary
        // check run yet, even though every action the cursor tracks is already terminal --
        // neither the automatic reservation retry nor the completion chat below may fire while
        // that re-visit could still change what "complete" actually means for this session.
        if (adapter.isSweepPending()) return
        // A session reaching completion -- whether it goes on to an automatic reservation retry
        // below or reports done -- proves the no-progress rebuild loop, if any, is broken: reset
        // the same way any dirty discard that actually placed something already does (see the
        // dirty-discard branch in tickPlanMode).
        planNoProgressCount = 0
        planRebuildBackoffTicksRemaining = 0
        planBackoffMessaged = false
        // The retry execution itself -- sweep/discard/return -- stays here, after the status
        // writes above, reusing the same willRetry value computed earlier rather than calling
        // shouldAutoRetryPlanSession a second time.
        if (willRetry) {
            planRetryUsed = true
            planRetryIdentity = currentIdentity
            sweepOutstandingScaffolds(adapter)
            planAdapter = null
            planSessionKey = null
            planBehaviorSettings = null
            // The coordinator is already IDLE at this point (its own job settled before this
            // session was ever adopted) -- cancel() is a harmless no-op here, called anyway to
            // keep every discard path uniform.
            planCoordinator?.cancel()
            return
        }
        if (planCompletionMessaged) return
        planCompletionMessaged = true
        // A reserved cell is real unplaced schematic work -- it must count toward the total the
        // same as a failed/skipped target, so this message never says plain "complete" while any
        // reservation remains unresolved.
        val unplaced = targetCounts.failedOrSkipped + planReservationCount
        if (unplaced == 0) {
            ChatSender.send("[nuchematica] plan complete")
        } else {
            ChatSender.send("[nuchematica] plan complete, $unplaced cells unplaced")
        }
    }

    private fun obtainPlanCoordinator(): PlanCoordinator {
        val existing = planCoordinator
        if (existing != null) return existing
        val created = PlanCoordinator(
            onPlanningFailure = { failure -> ChatSender.send("[nuchematica] plan failed: ${failure.message}") },
        )
        planCoordinator = created
        return created
    }

    // Lambdas read the CURRENT singletons at call time (never a bound `::` reference captured
    // once) -- this assembler is long-lived across every plan-mode build cycle, so it must keep
    // reflecting whatever missingLocal/content/transform actually are at call time. This
    // satisfies PlanCoordinator.advance's own caller-purity doc: the assembler only ever runs
    // inside advance() on the main thread, and any change to these sources is either an
    // identity change (content/transform, which restarts the coordinator's cycle) or a write-
    // revision bump (which the coordinator's own snapshot-vs-write-revision check catches).
    private fun obtainPlanContentAssembler(): PlanContentAssembler {
        val existing = planContentAssembler
        if (existing != null) return existing
        val created = BudgetedPlanContentAssembler(
            missingLocal = { MissingBlockHolder.missingSnapshot().missingLocal },
            expectedState = { local -> SchematicHolder.renderingBlocks.blocks[local] },
            localToWorld = { local -> SchematicRenderManager.localBlockToWorld(local) },
        )
        planContentAssembler = created
        return created
    }

    // Same +2-margin world-space AABB PrintWorldModel.ensureCapture itself computes for this
    // exact (content.blocks.keys, localBlockToWorld) pair (see SchematicRenderManager's own
    // initMissingBlock) -- constrains every scaffold chain's cells/anchor to the frozen world's
    // own captured region. The margin is duplicated here (PrintWorldModel.REGION_MARGIN is
    // private to that file) and must keep agreeing with it. Cached by identity (see
    // planBoundsIdentity's own doc) rather than recomputed on every call: the returned closure
    // only ever captures min/max BlockPos values, never level/content, so the SAME cached
    // instance stays valid for as long as identity keeps matching.
    private fun boundsPredicateFor(identity: PlanIdentity, content: SchematicCache): (BlockPos) -> Boolean {
        val cachedIdentity = planBoundsIdentity
        val cachedPredicate = planBoundsPredicate
        if (cachedIdentity != null && cachedPredicate != null && cachedIdentity.matches(identity)) {
            return cachedPredicate
        }
        val worldBounds = schematicWorldBoundingBox(
            content.blocks.keys,
            SchematicRenderManager::localBlockToWorld,
            margin = 2,
        )
        val predicate: (BlockPos) -> Boolean = if (worldBounds != null) {
            val (min, max) = worldBounds
            { pos -> pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z }
        } else {
            { true }
        }
        planBoundsIdentity = identity
        planBoundsPredicate = predicate
        return predicate
    }

    // Best-effort cleanup for a plan session about to be discarded (flag OFF, session identity
    // change, or invalidatePlanSession) -- mirrors cleanupScaffolds' own "never leave the
    // scaffold material in the world" invariant for the v3 path. Destroys every cell
    // outstandingScaffoldCells still finds standing as scaffold material in the live world (see
    // PlanRuntimeAdapter.sweepOutstandingScaffolds' own doc for the recordWrite-on-confirmed-air
    // half of this); the adapter itself is about to be discarded either way, so the break's own
    // ack is never awaited (nothing would be left to observe it). Requires the session's own
    // captured level (planSessionKey.level) to still BE the live level: a session built against
    // a since-unloaded ClientLevel (world reconnect/dimension change) must never destroy blocks
    // at its own stale BlockPos values in a DIFFERENT, unrelated level.
    private fun sweepOutstandingScaffolds(adapter: PlanRuntimeAdapter?): Unit {
        if (adapter == null) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val gameMode = minecraft.gameMode ?: return
        if (planSessionKey?.level !== level) return
        adapter.sweepOutstandingScaffolds(level::getBlockState, gameMode::destroyBlock, PrintWorldModel::recordWrite)
    }

    // Session identity change or flag OFF (see tick()'s branch) both discard the plan
    // session outright -- plan mode has no incremental resync path like v3's layer-gate
    // caches, since PrintPlanner.plan is a full re-classification anyway. Every existing
    // teardown trigger (toggle-off, null world, AUTO_DISABLED, non-READY mismatch/REFUSED
    // discard, flag OFF) already flows through here, so every plan-mode-episode tracking field
    // below (retry latch, REFUSED notice, build-phase/completion-message tracking) is reset in
    // exactly one place.
    private fun teardownPlanSession(): Unit {
        sweepOutstandingScaffolds(planAdapter)
        planCoordinator?.cancel()
        planSessionDirty = false
        planSessionKey = null
        planAdapter = null
        planBehaviorSettings = null
        planRetryUsed = false
        planRetryIdentity = null
        planRefusedNoticeKey = null
        planLastPhase = null
        planCompletionMessaged = false
        planPhase = null
        // An identity/behavior change or flag-off starts an unrelated episode -- a no-progress
        // streak or backoff counted against the discarded episode must never throttle this one.
        planNoProgressCount = 0
        planRebuildBackoffTicksRemaining = 0
        planBackoffMessaged = false
        planDiscardedIdentity = null
        // Dropped here, not just left for obtainPlanContentAssembler/boundsPredicateFor's own
        // lazy rebuild to overwrite later: the assembler's retained output can be millions of
        // entries, and the cached bounds identity strongly references the old ClientLevel --
        // both would otherwise outlive the session they were built for.
        planContentAssembler = null
        planBoundsIdentity = null
        planBoundsPredicate = null
    }

    // TEMP C3DBG (remove after the layer-pin investigation): classifies every remaining
    // missing position the way the gate/mover see it and logs the per-layer counts plus
    // the concrete "actionable" leftovers -- the ones that pin the gate. Called when
    // auto-move reports COMPLETE so the log shows exactly why it stopped.
    internal fun debugDumpPinState(trigger: String): Unit {
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) return
        val logger = LogUtils.getLogger()
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val deferred = deferralLedger.deferredSnapshot()
        logger.info(
            "C3DBG[{}] gateY={} {} missing={} deferredRecorded={}",
            trigger, layerGateY, layerGate.debugCounters(), missing.missingLocal.size, deferred.size,
        )
        val entries = missing.missingLocal.mapNotNull { localPos ->
            val expected = content.blocks[localPos] ?: return@mapNotNull null
            SchematicRenderManager.localBlockToWorld(localPos) to expected
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            content.blocks.containsKey(SchematicRenderManager.worldBlockToLocal(pos))
        }
        val playerFeetPos = minecraft.player?.position()
        for ((y, layer) in entries.groupBy { (worldPos, _) -> worldPos.y }.toSortedMap()) {
            val classification = classifyMissing(
                layer,
                PrintWorldModel::stateAt,
                missing.revision,
                deferralLedger,
                isSchematicPosition,
                playerFeetPos,
            )
            logger.info(
                "C3DBG[{}] y={} total={} excluded={} occupied={} unreachable={} deferred={} " +
                    "unsupported={} scaffoldAssisted={} actionable={}",
                trigger, y, layer.size, classification.excluded, classification.occupied,
                classification.unreachable, classification.deferred, classification.unsupported,
                classification.scaffoldAssisted, classification.actionable.size,
            )
            for ((worldPos, expected) in classification.actionable.take(6)) {
                logger.info("C3DBG[{}]   pin candidate {} expected {}", trigger, worldPos, expected)
            }
        }
        // TEMP C3DBG: name the non-actionable leftovers so a residual world can be
        // diagnosed by state, not just by count (deferred/unreachable/occupied).
        // Highest layers first: the interesting stragglers (roof/arch remnants) live at
        // the top, and the line cap below used to exhaust itself on lower layers.
        var leftoverLines = 0
        for ((worldPos, expected) in entries.sortedByDescending { (position, _) -> position.y }) {
            if (leftoverLines >= 48) break
            val causes = deferred[worldPos]
            val worldState = PrintWorldModel.stateAt(worldPos)
            val eligible = eligiblePrinterBlockItem(expected) != null
            val label = when {
                causes != null -> "deferred$causes"
                eligible && !isReplaceableTarget(worldState) -> "occupied by $worldState"
                eligible && !hasSupportNeighbor(worldPos, expected, PrintWorldModel::stateAt) -> {
                    // Tag each neighbor: S = solid in world, m = expected but missing, . = nothing.
                    val neighborTags = Direction.values().joinToString("") { direction ->
                        val neighborPos = worldPos.relative(direction)
                        when {
                            isSupportingState(PrintWorldModel.stateAt(neighborPos)) -> "S"
                            content.blocks.containsKey(
                                SchematicRenderManager.worldBlockToLocal(neighborPos),
                            ) -> "m"
                            else -> "."
                        }
                    }
                    "unsupported[DUNSWE=$neighborTags]"
                }
                else -> continue
            }
            // TEMP C3DBG: the most recent selector/runtime skip
            // reason recorded for this exact position, if any -- diagnoses a leftover
            // by WHY the printer itself last passed over it, not just its static
            // classification above.
            logger.info(
                "C3DBG[{}]   leftover {} expected {} ({}){}",
                trigger, worldPos, expected, label, lastSkipSuffix(skipPositionLog.lastSkipFor(worldPos)),
            )
            leftoverLines++
        }
        logger.info("C3DBG[{}] skips={}", trigger, runtime.skipLog.snapshot())
    }

    // Computed on the COMPLETE transition (SchematicMover.notifyTerminalTransition) to
    // explain what auto-move left behind, in the same terms debugDumpPinState logs.
    internal fun completeBreakdown(): PrinterCompleteBreakdown {
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return PrinterCompleteBreakdown(
                totalRemaining = 0,
                excluded = 0,
                unsupported = 0,
                scaffoldAssisted = 0,
                unreachable = 0,
                occupied = 0,
                deferred = 0,
                actionable = 0,
            )
        }
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val entries = missing.missingLocal.mapNotNull { localPos ->
            val expected = content.blocks[localPos] ?: return@mapNotNull null
            SchematicRenderManager.localBlockToWorld(localPos) to expected
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            content.blocks.containsKey(SchematicRenderManager.worldBlockToLocal(pos))
        }
        val classification = classifyMissing(
            entries,
            PrintWorldModel::stateAt,
            missing.revision,
            deferralLedger,
            isSchematicPosition,
            minecraft.player?.position(),
        )
        return PrinterCompleteBreakdown(
            totalRemaining = missing.missingLocal.size,
            excluded = classification.excluded,
            unsupported = classification.unsupported,
            scaffoldAssisted = classification.scaffoldAssisted,
            unreachable = classification.unreachable,
            occupied = classification.occupied,
            deferred = classification.deferred,
            actionable = classification.actionable.size,
        )
    }

    // Called only by MoverCore's fresh-tick empty-route confirmation. This executable
    // invariant prevents COMPLETE while the printer can still act on any non-deferred
    // missing block; the core leaves completePending armed and rebuilds again instead.
    //
    // A scaffold blocker (an outstanding ledger entry or an
    // in-flight pendingScaffoldStep) could otherwise refuse forever -- retry-exhausted
    // records are never evicted by tickBreaks (ScaffoldLedger's own doc: "outstanding
    // forever until cleanup/abort resolves it"), and nothing else on this path triggers
    // cleanup/abort. scaffoldTerminalOutcome resolves it in a provably bounded way
    // instead; see its doc for the termination trace.
    internal fun canAutoMoveComplete(): Boolean {
        val breakdown = completeBreakdown()
        // activeScaffoldPlan also counts as outstanding -- it can be
        // non-null with the ledger still empty (a fresh plan whose first cell's own
        // submission has not yet been acked, or keeps failing before ever landing).
        val scaffoldOutstanding = scaffoldLedger.isOutstanding() ||
            pendingScaffoldStep != null ||
            activeScaffoldPlan != null
        val outcome = scaffoldTerminalOutcome(
            actionable = breakdown.actionable,
            scaffoldOutstanding = scaffoldOutstanding,
            alreadyRearmedThisEncounter = scaffoldTerminalRearmed,
        )
        scaffoldTerminalRearmed = outcome == ScaffoldTerminalOutcome.REARM_AND_REFUSE
        when (outcome) {
            ScaffoldTerminalOutcome.REARM_AND_REFUSE -> {
                // Reachable here only when actionable == 0, i.e. the
                // pending plan's own target is no longer actionable (satisfied,
                // deferred, or unreachable) -- genuinely stale, not a live in-flight
                // need. One bounded extra round for retry-exhausted
                // ledger records.
                pendingScaffoldStep = null
                activeScaffoldPlan = null
                val rearmedCount = scaffoldLedger.rearmExhausted()
                LogUtils.getLogger().error(
                    "C3DBG[complete] rejected: scaffold outstanding, re-armed {} exhausted break(s) ledger={}",
                    rearmedCount,
                    scaffoldLedger.snapshot().size,
                )
            }
            ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP -> {
                // Still outstanding after the bounded round -- force a
                // synchronous cleanup (chat-reports any survivor and evicts it) rather
                // than refuse forever.
                pendingScaffoldStep = null
                activeScaffoldPlan = null
                if (scaffoldLedger.isOutstanding()) cleanupScaffolds()
            }
            ScaffoldTerminalOutcome.REFUSE -> {
                LogUtils.getLogger().error(
                    "C3DBG[complete] rejected: total={} actionable={} gateY={}",
                    breakdown.totalRemaining,
                    breakdown.actionable,
                    layerGateY,
                )
            }
            ScaffoldTerminalOutcome.ALLOW_CLEAR -> Unit
        }
        return outcome == ScaffoldTerminalOutcome.ALLOW_CLEAR ||
            outcome == ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP
    }

    // Best-effort cleanup for every exit path that can strand a
    // scaffold (abort/toggle-off/session change) -- must never silently leave the
    // scaffold material in the world. A pending (submitted-but-unacked) placement is
    // folded into the ledger as PLACED first; if it never actually landed,
    // ScaffoldLedger.cleanupAll's own isReplaceableTarget check finds the cell already
    // air and evicts it with zero leftover, so callers do not need to special-case
    // "in flight vs. already tracked" themselves.
    internal fun cleanupScaffolds(): Unit {
        val pending = pendingScaffoldStep
        if (!scaffoldLedger.isOutstanding() && pending == null && activeScaffoldPlan == null) return
        pendingScaffoldStep = null
        // Abandons whatever plan (single-cell or chain) was in
        // progress -- cleanupAll below tears down every cell the ledger already
        // tracks regardless of chain membership, so nothing further needs doing here
        // beyond forgetting the plan itself.
        activeScaffoldPlan = null
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val gameMode = minecraft.gameMode
        val leftover = if (level == null || gameMode == null) {
            // No live world/game mode to act on (e.g. world unload, disconnect): report
            // whatever was tracked and drop it -- nothing further this client can do.
            val count = scaffoldLedger.snapshot().size + if (pending != null) 1 else 0
            scaffoldLedger.clearAll()
            count
        } else {
            if (pending != null) scaffoldLedger.recordPlaced(pending.scaffoldPos, pending.targetPos)
            scaffoldLedger.cleanupAll(stateAt = level::getBlockState, destroy = gameMode::destroyBlock)
        }
        if (leftover > 0) {
            ChatSender.send("[nuchematica] scaffold cleanup: $leftover left")
        }
    }

    // Orphan scaffold sweep, run once per auto-move toggle-ON (see
    // SchematicMover.toggleRequested's TAKEOFF branch, right beside clearMoverCauses). A
    // disconnect/rejoin loses the ClientLevel before cleanupScaffolds (its own exit
    // paths) ever gets to run against it, so the ScaffoldLedger is cleared while a placed
    // scaffold cell is still physically standing -- the player then bounces off it into an
    // instant SERVER_CORRECTION abort. Scans the schematic's world-space bounding box (+2
    // margin) for slime blocks the schematic itself does not expect there
    // (findOrphanScaffolds) and folds each one straight into the
    // ledger's existing PLACED->CONSUMED break queue: recordPlaced with the orphan as its
    // own target, then markConsumed, since an orphan has no dependent real block to wait
    // for (the ledger itself is unchanged by this -- only its existing public API is used).
    // findOrphanScaffolds has no ledger visibility (it only sees world/schematic state), so
    // isScaffoldCell filters out any position the ledger already tracks -- otherwise a
    // scaffold this same printer session already placed (still PLACED, waiting on its
    // dependent real block, e.g. from ordinary manual-walking placement before auto-move
    // was toggled on) would be re-registered and torn down before that dependent block is
    // ever placed.
    internal fun sweepOrphanScaffolds(): Unit {
        val level = Minecraft.getInstance().level ?: return
        val content = SchematicHolder.renderingBlocks
        val bounds = schematicWorldBoundingBox(
            localPositions = content.blocks.keys,
            localToWorld = SchematicRenderManager::localBlockToWorld,
            margin = ORPHAN_SWEEP_MARGIN,
        ) ?: return
        val orphans = findOrphanScaffolds(
            boundsMin = bounds.first,
            boundsMax = bounds.second,
            expectedAt = { worldPos -> content.blocks[SchematicRenderManager.worldBlockToLocal(worldPos)] },
            stateAt = level::getBlockState,
            onBoundsExceeded = {
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] orphan sweep skipped: box too large min={} max={}",
                    bounds.first,
                    bounds.second,
                )
            },
        )
        for (orphan in orphans) {
            if (scaffoldLedger.isScaffoldCell(orphan)) continue
            scaffoldLedger.recordPlaced(orphan, orphan)
            scaffoldLedger.markConsumed(orphan)
        }
    }

    // Tries every gated missing position whose
    // only blocking factor is the lack of a usable support face (eligible + replaceable
    // target both hold, but hasSupport fails -- the exact complement isActionableMissing's
    // support term gates on, reusing isActionableMissing itself instead of re-deriving
    // eligibility/replaceable-target rules that already live there), in
    // gatedMissingLocal order, planning each until one succeeds. A single "first
    // unsupported position" pick (tried once) would permanently stall scaffold assist the
    // moment that ONE position has no valid plan (e.g. truly enclosed) even when other
    // unsupported positions elsewhere are perfectly scaffoldable -- every subsequent tick
    // would keep re-selecting the same unplannable position forever. Runs in every gate
    // phase (the RECOVERY/final-sweep-only gate is removed): gatedMissingLocal already
    // only contains at-or-below-gate positions, so an ASCENT-phase call only ever
    // considers the current climb's own layer and below.
    // Per position, ScaffoldPlanner.plan (the direct, single-cell case)
    // is tried first -- cheaper, and it is exactly the case a chain would degenerate to
    // anyway. Only when it returns null does ScaffoldChainPlanner.plan's BFS bridge
    // search run for that SAME position, before moving on to the next gated position.
    // Every read here (deferral revalidation, actionability, and
    // both planners' cell searches) goes through the frozen-world model instead of a live
    // ClientLevel -- the actual submission this plan feeds into still re-validates
    // against a live read (SchematicPrinter.tick's ordinary candidateSelector.select call
    // just below findScaffoldPlan's caller), so a stale model read here can only cost a
    // wasted plan, never a bad placement.
    private fun findScaffoldPlan(
        gatedMissingLocal: List<BlockPos>,
        content: SchematicCache,
        missingRevision: Long,
        eyePosition: Vec3,
        reach: Double,
        playerFeetPos: Vec3,
        isSchematicPosition: (BlockPos) -> Boolean,
    ): ScaffoldPlan? {
        val reachSquared = reach * reach
        for (localPos in gatedMissingLocal) {
            val expectedState = content.blocks[localPos] ?: continue
            val worldPos = SchematicRenderManager.localBlockToWorld(localPos)
            if (deferralLedger.isDeferred(worldPos, PrintWorldModel::stateAt, missingRevision)) continue
            if (isInPlayerColumn(worldPos, playerFeetPos)) continue
            val center = Vec3(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5)
            if (center.distanceToSqr(eyePosition) > reachSquared) continue
            val eligibleAndReplaceable = isActionableMissing(
                worldPos,
                expectedState,
                PrintWorldModel::stateAt,
                hasSupport = { _, _, _ -> true },
            )
            if (!eligibleAndReplaceable) continue
            val supported = isActionableMissing(
                worldPos,
                expectedState,
                PrintWorldModel::stateAt,
                hasSupport = layerGateSupportMemo::hasSupport,
            )
            if (supported) continue
            val directPlan = ScaffoldPlanner.plan(
                targetPos = worldPos,
                expectedState = expectedState,
                isSchematicPosition = isSchematicPosition,
                stateAt = PrintWorldModel::stateAt,
                playerFeetPos = playerFeetPos,
            )
            if (directPlan != null) return directPlan
            val chainPlan = ScaffoldChainPlanner.plan(
                targetPos = worldPos,
                expectedState = expectedState,
                isSchematicPosition = isSchematicPosition,
                stateAt = PrintWorldModel::stateAt,
                playerFeetPos = playerFeetPos,
            )
            if (chainPlan != null) return chainPlan
        }
        return null
    }

    private fun synchronizeLayerGateSession(
        levelIdentity: Any,
        contentIdentity: Any,
        transformRevision: Long,
    ): Unit {
        if (
            !layerGateSessionInitialized ||
            layerGateLevelIdentity !== levelIdentity ||
            layerGateContentIdentity !== contentIdentity ||
            layerGateTransformRevision != transformRevision
        ) {
            resetLayerGateSession()
            layerGateSessionInitialized = true
            layerGateLevelIdentity = levelIdentity
            layerGateContentIdentity = contentIdentity
            layerGateTransformRevision = transformRevision
        }
    }

    private fun resetLayerGateSession(): Unit {
        // A session change (fresh enable, or level/content/transform
        // identity change mid-session) is a mandatory scaffold cleanup trigger.
        cleanupScaffolds()
        layerGate.reset()
        layerGateLevelIdentity = null
        layerGateContentIdentity = null
        layerGateTransformRevision = 0L
        layerGateSessionInitialized = false
        layerGateUpdated = false
        layerGateY = null
        layerGatePhase = PrinterLayerGatePhase.ASCENT
        acceptedForLayerGate = 0
        previousLayerGatePlayerPosition = null
        // A session change is a fresh start for the terminal
        // scaffold guard too -- cleanupScaffolds above already cleared the ledger, so
        // any stale re-arm state must not carry into the new session.
        scaffoldTerminalRearmed = false
        // TEMP C3MOV: a session change is a fresh run for the
        // scaffold chain stats too.
        scaffoldCellsPlaced = 0
        scaffoldCellsBroken = 0
        scaffoldChainsUsed = 0
        scaffoldMaxChainLength = 0
        scaffoldStallTicks = 0
    }

    private const val GATE_REACH_MARGIN: Double = 1.0
    private const val PLAYER_MOVEMENT_DISTANCE_SQUARED: Double = 0.01
    private const val ORPHAN_SWEEP_MARGIN: Int = 2
    private const val SCAFFOLD_STALL_ABANDON_TICKS: Int = 200
}

internal data class LayerGateMissing(
    internal val localPos: BlockPos,
    internal val worldPos: BlockPos,
    internal val eligible: Boolean,
    internal val expectedState: BlockState,
)

// Entries are lazily memoized per position instead of an eager
// mapValues() pass over the WHOLE content map on every content/transform/eligibility
// change -- at 0_all's 3.5M-entry content, that eager pass alone would freeze the first
// printer tick after toggling P on. Every actual caller only ever queries get() for specific
// positions (the current missing set, itself a subset of content -- see
// SchematicPrinter.tick's layerGateMissingCache[localPos] call sites and
// LayerGateEligibleMissingCache's entryAt), never enumerates every entry, so a bounded
// per-position memo preserves the exact same query semantics at a fraction of the cost.
// Entries are a pure function of (localPos, expectedState, transformRevision,
// substituteLookalikes) -- synchronize() only decides whether that function has changed
// (a structural identity check, O(1)) and clears the memo if so; get() recomputes+caches
// on the first access per position after that, exactly like LayerGateSupportMemo/
// LayerGateScaffoldMemo's existing lazy-memo shape.
internal class LayerGateMissingCache(
    private val eligibleState: (BlockState) -> Boolean,
    private val localToWorld: (BlockPos) -> BlockPos,
) {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var substituteLookalikes: Boolean = true
    private var expectedStates: Map<BlockPos, BlockState> = emptyMap()
    private val memo: HashMap<BlockPos, LayerGateMissing> = HashMap()

    internal fun synchronize(
        contentIdentity: Any,
        expectedStates: Map<BlockPos, BlockState>,
        transformRevision: Long,
        substituteLookalikes: Boolean = true,
    ): Unit {
        val structurallyStale = this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision ||
            this.substituteLookalikes != substituteLookalikes
        if (structurallyStale) {
            memo.clear()
        }
        this.contentIdentity = contentIdentity
        this.expectedStates = expectedStates
        this.transformRevision = transformRevision
        this.substituteLookalikes = substituteLookalikes
    }

    internal operator fun get(localPos: BlockPos): LayerGateMissing? {
        memo[localPos]?.let { return it }
        val expectedState = expectedStates[localPos] ?: return null
        val entry = LayerGateMissing(
            localPos = localPos,
            worldPos = localToWorld(localPos),
            eligible = eligibleState(expectedState),
            expectedState = expectedState,
        )
        memo[localPos] = entry
        return entry
    }
}

internal data class LayerGateEligibleMissing(
    internal val sortedDistinctYs: List<Int>,
    // Collection, not List: snapshot() below hands out the live per-Y bucket view
    // (LinkedHashMap.values) with zero copying. Every consumer only ever calls
    // any{}/map{} on this, both of which are Iterable operations.
    internal val byY: Map<Int, Collection<LayerGateMissing>>,
)

// Replaces the old full-rebuild-per-missingRevision cache. rebuild()
// is the O(all-eligible-missing) pass -- called only on a genuine structural change
// (content/transform/substituteLookalikes identity) or once right after
// MissingBlockHolder.initialize(), both rare compared to per-placement traffic. Every
// ordinary placement instead flows through applyChange(), an O(1)-ish per-Y-bucket update
// driven by MissingBlockHolder.changesSince -- sync() is the single per-tick entry point
// that decides which path applies. rebuildCount is test-visible so a test can assert the
// structural guarantee directly: N sequential placements against an unchanged identity
// must not move it.
internal class LayerGateEligibleMissingCache(
    // Injectable so tests can prove the per-call budget structurally (a
    // small budget over a small dataset) instead of needing a 50k+-entry fixture to
    // exercise the multi-call path -- defaults to the exact production budget
    // (PrintWorldModel's own capture budget, "piggybacking the existing pump pattern"
    // rather than inventing a second tuning knob), so every existing/production call
    // site is unaffected. Declared before entryAt (not after) so entryAt stays the
    // LAST constructor parameter -- existing call sites use Kotlin's trailing-lambda
    // shorthand (`LayerGateEligibleMissingCache { localPos -> ... }`), which only binds
    // to the final parameter.
    private val rebuildBudget: Int = PrintWorldModel.CAPTURE_CELLS_PER_TICK,
    private val entryAt: (BlockPos) -> LayerGateMissing?,
) {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var substituteLookalikes: Boolean = true
    private var lastAppliedRevision: Long = 0L
    private val byY: HashMap<Int, LinkedHashMap<BlockPos, LayerGateMissing>> = HashMap()
    private val yByLocal: HashMap<BlockPos, Int> = HashMap()
    private var sortedYs: List<Int> = emptyList()
    private var sortedYsDirty: Boolean = false

    internal var rebuildCount: Int = 0
        private set

    // Budgeted rebuild state, mirroring
    // MissingBlockHolder's beginInitialize/pump/finishInitialize shape. Without
    // budgeting, a structural change (or a changesSince gap) would call rebuild() -- an
    // unconditional O(all-missing) pass -- synchronously inside sync(), which
    // SchematicPrinter.tick calls every tick: at 0_all's ~3.5M-entry missing set, the
    // FIRST tick after toggling the printer on (contentIdentity starts null, so
    // structurallyStale is always true that tick) would freeze the client on this
    // single call. pendingEntries is
    // non-null exactly while a rebuild episode spans more than one sync() call; the OLD
    // byY/yByLocal stay fully intact and readable (matching MissingBlockHolder's own
    // "never a torn result" guarantee) until the working copy swaps in atomically on
    // completion.
    private var pendingEntries: Iterator<BlockPos>? = null
    private var pendingContentIdentity: Any? = null
    private var pendingTransformRevision: Long = 0L
    private var pendingSubstituteLookalikes: Boolean = true
    // The missingRevision as of beginRebuild -- the revision the swapped-in
    // byY/yByLocal will actually reflect once the pass completes, since pendingEntries
    // iterates the missingLocal view captured at that moment (frozen, per
    // MissingLocalView's own doc), not whatever the current tick's missingRevision
    // happens to be. A rebuild can span several ticks; ordinary printer placement is
    // paused for that span by isRebuilding(), but a manual player block break/place is
    // reconciled through a separate path that calls MissingBlockHolder.placed()/
    // removed() directly every tick regardless of this cache's own state. Stamping the
    // COMPLETION-time revision as "caught up to" would silently drop any such change
    // that landed during the rebuild: byY would stay stuck at the begin-time view
    // forever, since lastAppliedRevision would (falsely) claim there is nothing left to
    // replay. Stamping the BEGIN-time revision instead means the very next sync()
    // call's ordinary changesSince(lastAppliedRevision) path replays everything that
    // happened during and after the rebuild, exactly like any other catch-up gap -- no
    // new machinery, just an honest revision stamp.
    private var pendingMissingRevisionAtBegin: Long = 0L
    private var workingByY: HashMap<Int, LinkedHashMap<BlockPos, LayerGateMissing>> = HashMap()
    private var workingYByLocal: HashMap<BlockPos, Int> = HashMap()

    // True from the tick a rebuild episode starts until the budgeted pass
    // finishes swapping its result in. SchematicPrinter.tick gates its own placement
    // work on this exactly like it already gates on PrintWorldModel.status() ==
    // CAPTURING / MissingBlockHolder.isInitializing() -- the snapshot() this cache
    // would return mid-rebuild is stale (still the PREVIOUS complete result, per the
    // no-torn-result guarantee above), not merely incomplete, so a caller that pressed
    // on regardless would work off outdated eligibility/Y-bucketing for however many
    // ticks the rebuild spans.
    internal fun isRebuilding(): Boolean = pendingEntries != null

    // Single per-tick entry point (mirrors the old synchronize()'s call shape, plus one
    // extra changesSince callback): a structural identity change forces one full rebuild;
    // otherwise the missingRevision delta since last tick is replayed via
    // MissingBlockHolder.changesSince, falling back to one rebuild only if that span is no
    // longer retained (a consumer that was idle for a long stretch, or a bulk
    // MissingBlockHolder.initialize() this cache has not observed yet). A rebuild already
    // in progress just gets pumped further; a NEW structural change arriving mid-rebuild
    // restarts it against the fresh identity (mirrors MissingBlockHolder.initialize()
    // being callable again while a previous pass is still in flight).
    internal fun sync(
        contentIdentity: Any,
        transformRevision: Long,
        substituteLookalikes: Boolean,
        missingRevision: Long,
        missingLocal: List<BlockPos>,
        changesSince: (Long) -> List<MissingBlockChange>?,
    ): LayerGateEligibleMissing {
        if (pendingEntries != null) {
            val stillSameTarget = pendingContentIdentity === contentIdentity &&
                pendingTransformRevision == transformRevision &&
                pendingSubstituteLookalikes == substituteLookalikes
            if (!stillSameTarget) {
                beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
            }
            // lastAppliedRevision is not touched here -- it only ever becomes correct
            // once pumpRebuild's completion swap-in sets it to the begin-time revision
            // (see pendingMissingRevisionAtBegin's doc). While still pumping, its stale
            // value is never read (every other branch of sync() is unreachable while
            // pendingEntries != null), so leaving it alone is safe.
            pumpRebuild(rebuildBudget)
            return snapshot()
        }
        val structurallyStale = this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision ||
            this.substituteLookalikes != substituteLookalikes
        if (structurallyStale) {
            beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
            pumpRebuild(rebuildBudget)
        } else if (lastAppliedRevision != missingRevision) {
            val changes = changesSince(lastAppliedRevision)
            if (changes != null) {
                for (change in changes) applyChange(change)
                lastAppliedRevision = missingRevision
            } else {
                beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
                pumpRebuild(rebuildBudget)
            }
        }
        return snapshot()
    }

    // Unbudgeted, complete-in-one-call rebuild -- kept for callers (tests, and any
    // future caller) that want the old synchronous "do it all now" behavior rather than
    // sync()'s tick-budgeted path. Implemented as beginRebuild + an unbounded pump so
    // the two paths share one body instead of two copies of the classification loop.
    internal fun rebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
        substituteLookalikes: Boolean = true,
    ): LayerGateEligibleMissing {
        // No missingRevision of its own (this path is independent of sync()'s revision
        // tracking) -- passes the current lastAppliedRevision through unchanged so
        // completion's swap-in sets it back to itself (a no-op) rather than to an
        // arbitrary value.
        beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, lastAppliedRevision)
        pumpRebuild(Int.MAX_VALUE)
        return snapshot()
    }

    private fun beginRebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
        substituteLookalikes: Boolean,
        missingRevisionAtBegin: Long,
    ): Unit {
        rebuildCount++
        pendingEntries = missingLocal.iterator()
        pendingContentIdentity = contentIdentity
        pendingTransformRevision = transformRevision
        pendingSubstituteLookalikes = substituteLookalikes
        pendingMissingRevisionAtBegin = missingRevisionAtBegin
        workingByY = HashMap()
        workingYByLocal = HashMap()
    }

    // Advances the in-progress rebuild by at most `budget` positions. No-op unless a
    // rebuild is actually pending. Swaps the working maps into byY/yByLocal atomically
    // only once every position has been visited -- see isRebuilding's doc for why the
    // OLD result must stay exactly as it was until then.
    private fun pumpRebuild(budget: Int): Unit {
        val entries = pendingEntries ?: return
        var processed = 0
        while (entries.hasNext() && processed < budget) {
            val localPos = entries.next()
            val entry = entryAt(localPos)
            if (entry != null && entry.eligible) {
                workingByY.getOrPut(entry.worldPos.y) { LinkedHashMap() }[localPos] = entry
                workingYByLocal[localPos] = entry.worldPos.y
            }
            processed++
        }
        if (entries.hasNext()) return
        byY.clear()
        byY.putAll(workingByY)
        yByLocal.clear()
        yByLocal.putAll(workingYByLocal)
        contentIdentity = pendingContentIdentity
        transformRevision = pendingTransformRevision
        substituteLookalikes = pendingSubstituteLookalikes
        // The swapped-in byY/yByLocal reflect missingLocal exactly as it stood at
        // beginRebuild, not "now" -- see pendingMissingRevisionAtBegin's doc for why
        // stamping anything later here would silently drop concurrent changes.
        lastAppliedRevision = pendingMissingRevisionAtBegin
        sortedYsDirty = true
        pendingEntries = null
        workingByY = HashMap()
        workingYByLocal = HashMap()
    }

    internal fun applyChange(change: MissingBlockChange): Unit {
        if (!change.overlayChanged) return
        val existingY = yByLocal.remove(change.localPos)
        if (existingY != null) {
            val bucket = byY[existingY]
            bucket?.remove(change.localPos)
            if (bucket != null && bucket.isEmpty()) {
                byY.remove(existingY)
                sortedYsDirty = true
            }
        }
        if (!change.satisfied) {
            val entry = entryAt(change.localPos)
            if (entry != null && entry.eligible) {
                val isNewY = entry.worldPos.y !in byY
                byY.getOrPut(entry.worldPos.y) { LinkedHashMap() }[change.localPos] = entry
                yByLocal[change.localPos] = entry.worldPos.y
                if (isNewY) sortedYsDirty = true
            }
        }
    }

    internal fun snapshot(): LayerGateEligibleMissing {
        if (sortedYsDirty) {
            sortedYs = byY.keys.sorted()
            sortedYsDirty = false
        }
        return LayerGateEligibleMissing(
            sortedDistinctYs = sortedYs,
            byY = byY.mapValues { (_, bucket) -> bucket.values },
        )
    }
}

internal class LayerGateSupportMemo {
    private var missingRevision: Long = 0L
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val supportedByWorld: HashMap<BlockPos, Boolean> = HashMap()

    internal fun synchronize(
        missingRevision: Long,
        transformRevision: Long,
        contentIdentity: Any,
        levelIdentity: Any,
    ): Unit {
        if (
            this.missingRevision != missingRevision ||
            this.transformRevision != transformRevision ||
            this.contentIdentity !== contentIdentity ||
            this.levelIdentity !== levelIdentity
        ) {
            supportedByWorld.clear()
        }
        this.missingRevision = missingRevision
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    internal fun hasSupport(
        worldPos: BlockPos,
        expectedState: BlockState,
        stateAt: (BlockPos) -> BlockState,
    ): Boolean {
        return supportedByWorld.getOrPut(worldPos.immutable()) {
            hasSupportNeighbor(worldPos, expectedState, stateAt)
        }
    }
}

// Parallels LayerGateSupportMemo but for the scaffold-assist term.
// Removing the RECOVERY/final-sweep-only scaffoldAssistActive gate means the
// layer gate's isLayerSupported/inReach calculations must be able to ask "can this be
// scaffolded" every tick, in every phase, at the same cost hasSupportNeighbor already
// pays: ScaffoldPlanner.plan reads up to 6 neighbor states per candidate direction, the
// same order of cost as hasSupportNeighbor's own 6-direction scan, so it gets the
// identical memoization treatment (same four-key invalidation).
// isScaffoldAssistable's chain fallback (a bounded BFS) is more expensive than the direct
// case, which is exactly why this memo matters more, not less, now -- the chain search
// for a given position only ever actually runs once per invalidation window.
internal class LayerGateScaffoldMemo {
    private var missingRevision: Long = 0L
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val scaffoldableByWorld: HashMap<BlockPos, Boolean> = HashMap()

    internal fun synchronize(
        missingRevision: Long,
        transformRevision: Long,
        contentIdentity: Any,
        levelIdentity: Any,
    ): Unit {
        if (
            this.missingRevision != missingRevision ||
            this.transformRevision != transformRevision ||
            this.contentIdentity !== contentIdentity ||
            this.levelIdentity !== levelIdentity
        ) {
            scaffoldableByWorld.clear()
        }
        this.missingRevision = missingRevision
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    internal fun canScaffold(
        worldPos: BlockPos,
        expectedState: BlockState,
        stateAt: (BlockPos) -> BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        playerFeetPos: Vec3?,
    ): Boolean {
        return scaffoldableByWorld.getOrPut(worldPos.immutable()) {
            isScaffoldAssistable(worldPos, expectedState, stateAt, isSchematicPosition, playerFeetPos)
        }
    }
}

// TEMP C3DBG (remove with the rest of the layer-pin
// investigation): the most recent PrinterSkipReason seen for each worldPos, capped so a
// long-running session cannot grow this unboundedly. Access-order LinkedHashMap +
// removeEldestEntry is the standard bounded-LRU idiom: re-recording an already-tracked
// position refreshes it to most-recently-used instead of leaving it due for eviction.
internal class PrinterSkipPositionLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val reasonByPosition = object : LinkedHashMap<BlockPos, PrinterSkipReason>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BlockPos, PrinterSkipReason>): Boolean {
            return size > capacity
        }
    }

    internal fun record(reason: PrinterSkipReason, pos: BlockPos): Unit {
        reasonByPosition[pos.immutable()] = reason
    }

    internal fun lastSkipFor(pos: BlockPos): PrinterSkipReason? {
        return reasonByPosition[pos.immutable()]
    }

    internal companion object {
        internal const val DEFAULT_CAPACITY: Int = 256
    }
}

// TEMP C3DBG: pure formatting for debugDumpPinState's per-leftover
// lastSkip suffix, a top-level function so it is unit-testable on its own.
internal fun lastSkipSuffix(reason: PrinterSkipReason?): String {
    return if (reason != null) " lastSkip=$reason" else ""
}

// The terminal-confirm outcome for canAutoMoveComplete's
// scaffold-blocker resolution. A pure function (no ScaffoldLedger/Minecraft access) so
// the once-per-encounter guard is unit-testable without a live ClientLevel -- the same
// reasoning classifyMissing documents.
//
// Termination trace: starting from alreadyRearmedThisEncounter == false, hold
// actionable == 0 and scaffoldOutstanding == true across every call (the worst case --
// nothing ever naturally clears the scaffold). Call 1 returns REARM_AND_REFUSE, and the
// caller's contract is `rearmed := (outcome == REARM_AND_REFUSE)`, so rearmed becomes
// true. Call 2 (any later call under the same held inputs) then takes the
// alreadyRearmedThisEncounter branch and returns ALLOW_FORCE_CLEANUP, which the same
// caller contract turns back into rearmed = false. So the sequence of outcomes under
// sustained refusal is REARM_AND_REFUSE, ALLOW_FORCE_CLEANUP, ALLOW_FORCE_CLEANUP, ... --
// it can never return to REARM_AND_REFUSE without scaffoldOutstanding first going false
// (which reaches ALLOW_CLEAR and also resets rearmed to false, restarting a fresh
// encounter). Two calls bound the worst case; it never loops.
internal enum class ScaffoldTerminalOutcome {
    ALLOW_CLEAR,
    ALLOW_FORCE_CLEANUP,
    REARM_AND_REFUSE,
    REFUSE,
}

internal fun scaffoldTerminalOutcome(
    actionable: Int,
    scaffoldOutstanding: Boolean,
    alreadyRearmedThisEncounter: Boolean,
): ScaffoldTerminalOutcome {
    if (actionable != 0) return ScaffoldTerminalOutcome.REFUSE
    if (!scaffoldOutstanding) return ScaffoldTerminalOutcome.ALLOW_CLEAR
    return if (alreadyRearmedThisEncounter) {
        ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP
    } else {
        ScaffoldTerminalOutcome.REARM_AND_REFUSE
    }
}

// Per-reason counts of why a still-missing position isn't being worked on right now;
// see classifyMissing below for the classification rules. Surfaced to the player on the
// auto-move COMPLETE chat message (SchematicMover.notifyTerminalTransition) so "complete"
// with leftovers is never mistaken for "finished".
internal data class PrinterCompleteBreakdown(
    internal val totalRemaining: Int,
    internal val excluded: Int,
    internal val unsupported: Int,
    internal val scaffoldAssisted: Int,
    internal val unreachable: Int,
    internal val occupied: Int,
    internal val deferred: Int,
    internal val actionable: Int,
)

// TEMP C3MOV: scaffold chain stats for the auto-move terminal
// summary line -- see SchematicPrinter.scaffoldTelemetry's doc for what each field counts.
internal data class PrinterScaffoldTelemetry(
    internal val placed: Int,
    internal val broken: Int,
    internal val chains: Int,
    internal val maxChainLength: Int,
)

internal data class MissingClassification(
    internal val excluded: Int,
    internal val occupied: Int,
    internal val unreachable: Int,
    internal val deferred: Int,
    internal val unsupported: Int,
    internal val scaffoldAssisted: Int,
    internal val actionable: List<Pair<BlockPos, BlockState>>,
)

// Shared classification pass behind debugDumpPinState's per-layer log and
// completeBreakdown's COMPLETE chat summary: buckets each (worldPos, expectedState) pair
// by the first reason it is not actionable right now, using the same
// eligiblePrinterBlockItem/isReplaceableTarget/hasSupportNeighbor/isScaffoldAssistable
// terms isActionableMissing is composed from -- per-term branching (rather than a single
// isActionableMissing() call) is unavoidable here because these two call sites need to
// attribute WHY a position is not actionable, which a single boolean predicate cannot
// report. Active ledger causes are revalidated here so the terminal check cannot rely on
// a stale diagnostic snapshot. MOVER_UNREACHABLE remains its own bucket; NO_PROGRESS and
// printer causes use deferred.
//
// A position with no real support but a valid ScaffoldPlanner cell
// is bucketed as scaffoldAssisted -- reported separately for visibility in the
// COMPLETE breakdown, but counted into `actionable` exactly like a
// genuinely supported position, since the shared isActionableMissing predicate treats
// it the same way (canAutoMoveComplete's own "actionable == 0" formula is therefore
// unchanged: a scaffold-plannable leftover already blocks COMPLETE through the same
// field it always has). Unplannable positions stay unsupported.
//
// A top-level function (not a SchematicPrinter method) so it is unit-testable without a
// live Minecraft/ClientLevel singleton: SchematicPrinter's two call sites simply pass
// their own deferralLedger, level::getBlockState, and a schematic-position lambda.
internal fun classifyMissing(
    entries: List<Pair<BlockPos, BlockState>>,
    stateAt: (BlockPos) -> BlockState,
    queueRevision: Long,
    deferralLedger: PrinterDeferralLedger,
    isSchematicPosition: (BlockPos) -> Boolean,
    playerFeetPos: Vec3?,
): MissingClassification {
    var excluded = 0
    var occupied = 0
    var unreachable = 0
    var deferredCount = 0
    var unsupported = 0
    var scaffoldAssisted = 0
    val actionable = mutableListOf<Pair<BlockPos, BlockState>>()
    for ((worldPos, expected) in entries) {
        if (eligiblePrinterBlockItem(expected) == null) {
            excluded++
            continue
        }
        if (!isReplaceableTarget(stateAt(worldPos))) {
            occupied++
            continue
        }
        val activeCauses = deferralLedger.activeReasons(worldPos, stateAt, queueRevision)
        when {
            PrinterDeferralReason.MOVER_UNREACHABLE in activeCauses -> unreachable++
            activeCauses.isNotEmpty() -> deferredCount++
            hasSupportNeighbor(worldPos, expected, stateAt) -> actionable.add(worldPos to expected)
            isScaffoldAssistable(worldPos, expected, stateAt, isSchematicPosition, playerFeetPos) -> {
                scaffoldAssisted++
                actionable.add(worldPos to expected)
            }
            else -> unsupported++
        }
    }
    return MissingClassification(
        excluded = excluded,
        occupied = occupied,
        unreachable = unreachable,
        deferred = deferredCount,
        unsupported = unsupported,
        scaffoldAssisted = scaffoldAssisted,
        actionable = actionable,
    )
}

// Look direction (not the M2 functional PlacementRotation) toward a hit location, used
// only to pick a camera-ease target. Named distinctly from printer.PlacementRotation
// (Float, defined in PrinterCandidateSelector.kt) to avoid a same-package redeclaration.
internal data class LookRotation(
    internal val yaw: Double,
    internal val pitch: Double,
)

internal fun placementRotation(eyePosition: Vec3, hitLocation: Vec3): LookRotation {
    val dx = hitLocation.x - eyePosition.x
    val dy = hitLocation.y - eyePosition.y
    val dz = hitLocation.z - eyePosition.z
    return LookRotation(
        yaw = Math.toDegrees(atan2(-dx, dz)),
        pitch = Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))),
    )
}

// Holds the camera's target look direction and eases the player's actual rotation
// toward it a limited number of degrees per tick, instead of snapping instantly.
// The target is set once (on the first submit of a tick); SchematicPrinter.tick then
// calls ease() every tick while facePlacement is on to advance toward it smoothly.
internal class CameraEase {
    private var targetYaw: Float? = null
    private var targetPitch: Float? = null

    internal fun setTarget(yaw: Float, pitch: Float): Unit {
        targetYaw = yaw
        targetPitch = pitch
    }

    internal fun ease(currentYaw: Float, currentPitch: Float): Pair<Float, Float> {
        val yaw = targetYaw ?: return currentYaw to currentPitch
        val pitch = targetPitch ?: currentPitch
        return step(currentYaw, yaw, MAX_STEP_DEGREES) to step(currentPitch, pitch, MAX_STEP_DEGREES)
    }

    internal companion object {
        internal const val MAX_STEP_DEGREES: Float = 15f

        // Pure single-axis step: moves current toward target by at most maxStep degrees,
        // taking the shorter way around the +/-180 wrap point.
        internal fun step(current: Float, target: Float, maxStep: Float): Float {
            var delta = (target - current) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return when {
                delta > maxStep -> current + maxStep
                delta < -maxStep -> current - maxStep
                else -> current + delta
            }
        }
    }
}

internal class FacePlacementGateway(
    private val delegate: PlacementGateway,
    private val facePlacement: Boolean,
    private val eyePosition: Vec3,
    private val setCameraTarget: (Float, Float) -> Unit,
) : PlacementGateway {
    private var submitted: Boolean = false

    override fun submit(hit: BlockHitResult, requiredRotation: PlacementRotation?): Boolean {
        if (!submitted) {
            submitted = true
            if (facePlacement) {
                val rotation = placementRotation(eyePosition, hit.location)
                setCameraTarget(rotation.yaw.toFloat(), rotation.pitch.toFloat())
            }
        }
        return delegate.submit(hit, requiredRotation)
    }
}

internal class RealPlacementGateway(
    private val gameMode: MultiPlayerGameMode,
    private val player: LocalPlayer,
    private val level: ClientLevel,
) : PlacementGateway {
    override fun submit(hit: BlockHitResult, requiredRotation: PlacementRotation?): Boolean {
        if (requiredRotation == null) {
            return gameMode.useItemOn(player, level, InteractionHand.MAIN_HAND, hit).consumesAction()
        }
        // M2 oriented placement: rotate to the required facing, sync it to the server
        // (useItemOn does not send any rotation packet of its own -- verified against
        // MultiPlayerGameMode bytecode), place, then restore both the local fields and
        // the server-side rotation tracker with a matching packet.
        val originalYaw = player.yRot
        val originalPitch = player.xRot
        return submitOrientedPlacement(
            requiredRotation = requiredRotation,
            originalRotation = PlacementRotation(originalYaw, originalPitch),
            setLocalRotation = { rotation ->
                player.setYRot(rotation.yaw)
                player.setXRot(rotation.pitch)
            },
            sendServerRotation = { rotation ->
                player.connection.send(
                    ServerboundMovePlayerPacket.Rot(rotation.yaw, rotation.pitch, player.isOnGround),
                )
            },
            useItemOn = {
                gameMode.useItemOn(player, level, InteractionHand.MAIN_HAND, hit).consumesAction()
            },
        )
    }
}

internal fun submitOrientedPlacement(
    requiredRotation: PlacementRotation,
    originalRotation: PlacementRotation,
    setLocalRotation: (PlacementRotation) -> Unit,
    sendServerRotation: (PlacementRotation) -> Unit,
    useItemOn: () -> Boolean,
): Boolean {
    return try {
        setLocalRotation(requiredRotation)
        sendServerRotation(requiredRotation)
        useItemOn()
    } finally {
        setLocalRotation(originalRotation)
        sendServerRotation(originalRotation)
    }
}
