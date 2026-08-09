package com.watchtastic.mesh

import com.watchtastic.mesh.model.NodePosition
import com.watchtastic.mesh.model.bearingLabel
import com.watchtastic.mesh.model.formatDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compass and the map both live or die on this arithmetic, and neither is easy to
 * eyeball on a 1.2" screen — a needle pointing confidently at the wrong hill looks
 * exactly like one pointing at the right hill.
 */
class GeoTest {

    private val seattle = NodePosition(latitude = 47.6205, longitude = -122.3493)

    @Test
    fun `distance to itself is zero`() {
        assertEquals(0.0, seattle.distanceTo(seattle), 0.5)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val northOne = seattle.copy(latitude = seattle.latitude + 1.0)
        // Accepted geodesy: a degree of latitude is ~110.6 km at this latitude.
        assertEquals(111_000.0, seattle.distanceTo(northOne), 1_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val other = NodePosition(latitude = 47.6512, longitude = -122.3011)
        assertEquals(seattle.distanceTo(other), other.distanceTo(seattle), 0.001)
    }

    @Test
    fun `cardinal bearings come out where you would point`() {
        val north = seattle.copy(latitude = seattle.latitude + 0.5)
        val south = seattle.copy(latitude = seattle.latitude - 0.5)
        val east = seattle.copy(longitude = seattle.longitude + 0.5)
        val west = seattle.copy(longitude = seattle.longitude - 0.5)

        assertEquals(0f, seattle.bearingTo(north), 1f)
        assertEquals(180f, seattle.bearingTo(south), 1f)
        assertEquals(90f, seattle.bearingTo(east), 2f)
        assertEquals(270f, seattle.bearingTo(west), 2f)
    }

    @Test
    fun `bearings are always normalised into 0 until 360`() {
        val west = seattle.copy(longitude = seattle.longitude - 0.5)
        val bearing = seattle.bearingTo(west)
        assertTrue("bearing $bearing outside 0..360", bearing in 0f..360f)
    }

    @Test
    fun `bearing labels map to the right compass points`() {
        assertEquals("N", bearingLabel(0f))
        assertEquals("E", bearingLabel(90f))
        assertEquals("S", bearingLabel(180f))
        assertEquals("W", bearingLabel(270f))
        assertEquals("NE", bearingLabel(45f))
        // Wrapping past 360 must not fall off the end of the table.
        assertEquals("N", bearingLabel(360f))
        assertEquals("N", bearingLabel(720f))
    }

    @Test
    fun `distances switch units at a sensible threshold`() {
        assertEquals("250 m", formatDistance(250.0, imperial = false))
        assertEquals("1.5 km", formatDistance(1_500.0, imperial = false))
        // Imperial crosses over from feet to miles.
        assertTrue(formatDistance(100.0, imperial = true).endsWith("ft"))
        assertTrue(formatDistance(5_000.0, imperial = true).endsWith("mi"))
    }
}
