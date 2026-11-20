package com.watchtastic.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshTimeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `a real recent time passes through`() {
        assertEquals(1_799_999_000L, MeshTime.plausibleEpochSeconds(1_799_999_000, now))
    }

    @Test
    fun `uptime seconds are not a date`() {
        // A clockless radio stamps with seconds since boot.
        assertNull(MeshTime.plausibleEpochSeconds(0, now))
        assertNull(MeshTime.plausibleEpochSeconds(86_400, now))
        assertNull(MeshTime.plausibleEpochSeconds((MeshTime.MIN_PLAUSIBLE_EPOCH_S - 1).toInt(), now))
    }

    @Test
    fun `the floor itself is accepted`() {
        assertEquals(
            MeshTime.MIN_PLAUSIBLE_EPOCH_S,
            MeshTime.plausibleEpochSeconds(MeshTime.MIN_PLAUSIBLE_EPOCH_S.toInt(), now),
        )
    }

    @Test
    fun `small clock skew is tolerated, a clock far ahead is not`() {
        val nowS = now / 1000
        assertEquals(nowS + 60, MeshTime.plausibleEpochSeconds((nowS + 60).toInt(), now))
        assertNull(MeshTime.plausibleEpochSeconds((nowS + 3_600).toInt(), now))
    }

    @Test
    fun `fixed32 times after 2038 are read unsigned`() {
        // 2040-01-01 does not fit a signed int; Java hands it over negative.
        val y2040 = 2_208_988_800L
        val asWire = y2040.toInt()
        assertEquals(y2040, MeshTime.plausibleEpochSeconds(asWire, y2040 * 1000))
    }
}
