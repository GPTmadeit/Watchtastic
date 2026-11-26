package com.watchtastic.service

/**
 * Lets an alert through at most once per [minIntervalMs].
 *
 * Sender numbers on the mesh are unauthenticated, so per-node muting can't stop a
 * stranger rotating through them: each "new" node or "new" sender gets its own alert. A
 * limiter keyed on nothing but time doesn't care who is talking, which is the point. It
 * guards the wrist and the battery rather than hiding anything — suppressed events are
 * still recorded and shown, they just don't buzz.
 *
 * Not thread-safe; callers collect on a single coroutine.
 */
class AlertThrottle(
    private val minIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastAllowedAt = Long.MIN_VALUE

    /** True if an alert may fire now, and records that it did. */
    fun tryAcquire(): Boolean {
        val now = clock()
        // A clock that stepped backwards must not silence alerts until it catches up.
        val elapsed = if (lastAllowedAt == Long.MIN_VALUE || now < lastAllowedAt) {
            Long.MAX_VALUE
        } else {
            now - lastAllowedAt
        }
        if (elapsed < minIntervalMs) return false
        lastAllowedAt = now
        return true
    }
}
