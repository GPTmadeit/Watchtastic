package com.watchtastic.mesh

/**
 * Radio timestamps, which are only as good as the radio's clock.
 *
 * A radio with no GPS has no wall clock until a client gives it one. Until then the
 * firmware stamps packets with *uptime* seconds (flagging `rx_time` absent), and some
 * node-DB `last_heard` values are boot-relative too. Read as dates, those land in 1970 —
 * a node heard a minute ago shows as heard 56 years ago, and a fresh message sorts before
 * everything else. Anything that can't be a real date is treated as "unknown" instead.
 */
object MeshTime {
    /**
     * 2020-01-01, the same floor the firmware applies (`MIN_PLAUSIBLE_EPOCH`). Anything
     * earlier is uptime, not a date.
     */
    const val MIN_PLAUSIBLE_EPOCH_S = 1_577_836_800L

    /** A radio clock this far ahead of the watch is wrong, not early. */
    const val MAX_FUTURE_SKEW_S = 5 * 60L

    /**
     * Seconds since 1970 from a `fixed32` time field, or null when it can't be a genuine
     * wall-clock reading.
     *
     * The wire type is unsigned; Java sees it as a signed int, so it is widened without
     * sign extension — otherwise every timestamp after January 2038 would go negative.
     */
    fun plausibleEpochSeconds(raw: Int, nowMs: Long): Long? {
        val seconds = Integer.toUnsignedLong(raw)
        if (seconds < MIN_PLAUSIBLE_EPOCH_S) return null
        if (seconds > nowMs / 1000L + MAX_FUTURE_SKEW_S) return null
        return seconds
    }
}
