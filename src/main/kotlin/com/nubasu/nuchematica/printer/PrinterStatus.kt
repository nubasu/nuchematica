package com.nubasu.nuchematica.printer

public data class PrinterStatus(
    public val remaining: Int,
    public val placed: Int,
    public val skips: Map<PrinterSkipReason, Int>,
    // Plan-mode (v4) HUD phase -- null on every v3 construction site, so the v3 HUD stays
    // byte-for-byte unchanged. See SchematicPrinter's own planPhase field for the values this
    // takes and PrinterHudFormatter.lines for where it renders.
    internal val planPhase: String? = null,
)

public class PrinterStatusTracker {
    private var levelIdentity: Any? = null
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var placed: Int = 0
    private var initialized: Boolean = false

    public fun update(
        levelIdentity: Any,
        contentIdentity: Any,
        transformRevision: Long,
        remaining: Int,
        acceptedDelta: Int,
        skips: Map<PrinterSkipReason, Int>,
    ): PrinterStatus {
        if (!initialized ||
            this.levelIdentity !== levelIdentity ||
            this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision
        ) {
            placed = 0
        }

        initialized = true
        this.levelIdentity = levelIdentity
        this.contentIdentity = contentIdentity
        this.transformRevision = transformRevision
        placed += acceptedDelta
        return PrinterStatus(
            remaining = remaining,
            placed = placed,
            skips = skips.toMap(),
        )
    }

    public fun reset(): Unit {
        levelIdentity = null
        contentIdentity = null
        transformRevision = 0L
        placed = 0
        initialized = false
    }
}

public object PrinterHudFormatter {
    public fun lines(status: PrinterStatus): List<String> {
        val skipped = mutableListOf<Pair<String, Int>>()
        addSkip(skipped, status, PrinterSkipReason.OUT_OF_REACH, "reach")
        addSkip(skipped, status, PrinterSkipReason.NO_SUPPORT_FACE, "face")
        addSkip(skipped, status, PrinterSkipReason.PREDICTION_MISMATCH, "predict")
        addSkip(skipped, status, PrinterSkipReason.CATEGORY_EXCLUDED, "category")
        addSkip(skipped, status, PrinterSkipReason.RETRY_LIMIT, "retry")

        val skippedLine = if (skipped.isEmpty()) {
            "Skipped: 0"
        } else {
            val total = skipped.sumOf { it.second }
            val details = skipped.joinToString(", ") { (label, count) -> "$label: $count" }
            "Skipped: $total ($details)"
        }
        val lines = mutableListOf(
            "Printer: ACTIVE",
            "Remaining: ${status.remaining}",
            "Placed: ${status.placed}",
            skippedLine,
        )
        val planPhase = status.planPhase
        if (planPhase != null) lines += "Plan: $planPhase"
        return lines
    }

    private fun addSkip(
        skipped: MutableList<Pair<String, Int>>,
        status: PrinterStatus,
        reason: PrinterSkipReason,
        label: String,
    ): Unit {
        val count = status.skips[reason] ?: 0
        if (count > 0) skipped += label to count
    }
}
