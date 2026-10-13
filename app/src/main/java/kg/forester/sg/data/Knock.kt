package kg.forester.sg.data

/**
 * One timing pull by the ECU: the knock correction went down to [KnockStats.START_DEG] or lower and stayed
 * negative until [durationMs] later. The engine values are taken at the deepest point of the pull.
 */
data class KnockEvent(
    val startMs: Long,
    val durationMs: Long = 0,
    val maxPullDeg: Float,
    val rpm: Int?,
    val loadGRev: Float?,
    val throttlePct: Float?,
    val speedKmh: Int?,
    val coolantC: Int?,
    val timingDeg: Float?,
    val active: Boolean = true,
)

/** Timing pulls since the current trip started. */
data class KnockStats(val sinceMs: Long = 0, val events: List<KnockEvent> = emptyList()) {
    val count get() = events.size
    /** The deepest pull of the trip, a negative number of degrees. */
    val maxPullDeg get() = events.minOfOrNull { it.maxPullDeg }

    companion object {
        /** A pull of -0.5° (one ECU step) is too common to count; it still shows live. */
        const val START_DEG = -1f
    }
}
