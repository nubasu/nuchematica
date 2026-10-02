package com.nubasu.nuchematica.mover

import kotlin.math.roundToInt

public object MoverHudFormatter {
    public fun lines(status: MoverStatus): List<String> {
        val stateLine = if (
            status.state == MoverState.ABORTED && status.abortReason != null
        ) {
            "Auto-move: ABORTED (${status.abortReason})"
        } else {
            "Auto-move: ${status.state}"
        }
        return buildList {
            add(stateLine)
            add("Blocks left: ${status.remainingMissing}")
            status.target?.let { target ->
                add(
                    "Target: ${target.x.roundToInt()}," +
                        "${target.y.roundToInt()},${target.z.roundToInt()}",
                )
            }
        }
    }
}
