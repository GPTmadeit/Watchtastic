package com.watchtastic.mesh

import com.watchtastic.mesh.model.NodeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetry arrives one variant at a time and every field is optional, so "is there
 * anything to show?" is a real question rather than a formality — a weather station
 * reporting only lightning has no battery reading at all.
 */
class TelemetryTest {

    @Test
    fun `empty metrics have nothing worth drawing a card for`() {
        assertFalse(NodeMetrics().hasTelemetry)
    }

    @Test
    fun `a node reporting only lightning still gets a telemetry card`() {
        // This is the case the old hand-rolled check missed: it looked at battery and
        // temperature only, so an AS3935 weather station rendered as an empty screen.
        val stormOnly = NodeMetrics(lightningStrikes1h = 4, lightningDistanceKm = 8.5f)
        assertTrue(stormOnly.hasTelemetry)
    }

    @Test
    fun `any single reading is enough`() {
        assertTrue(NodeMetrics(batteryLevel = 80).hasTelemetry)
        assertTrue(NodeMetrics(voltage = 3.9f).hasTelemetry)
        assertTrue(NodeMetrics(temperature = 11.5f).hasTelemetry)
        assertTrue(NodeMetrics(uptimeSeconds = 60).hasTelemetry)
        assertTrue(NodeMetrics(channelUtilization = 6.4f).hasTelemetry)
    }

    @Test
    fun `lightning is only flagged when strikes were actually heard`() {
        assertFalse(NodeMetrics().hasLightning)
        assertFalse(NodeMetrics(lightningStrikes1h = 0).hasLightning)
        assertTrue(NodeMetrics(lightningStrikes1h = 1).hasLightning)
    }

    @Test
    fun `distance without strikes does not claim a storm`() {
        // A sensor can report a stale distance with a zeroed hourly count; that is not
        // an active storm and must not read as one.
        val stale = NodeMetrics(lightningStrikes1h = 0, lightningDistanceKm = 12f)
        assertFalse(stale.hasLightning)
        assertTrue(stale.hasTelemetry)
    }

    @Test
    fun `battery over one hundred means external power, not a full battery`() {
        assertTrue(NodeMetrics(batteryLevel = 101).isPluggedIn)
        assertFalse(NodeMetrics(batteryLevel = 100).isPluggedIn)
        assertFalse(NodeMetrics().isPluggedIn)
    }

    @Test
    fun `metrics merge does not lose fields the newest packet omitted`() {
        // Telemetry packets carry one variant at a time, so a device-metrics packet
        // arriving after an environment one must not erase the temperature.
        val existing = NodeMetrics(temperature = 11.5f, lightningStrikes1h = 3)
        val merged = existing.copy(batteryLevel = 78)
        assertEquals(11.5f, merged.temperature)
        assertEquals(3, merged.lightningStrikes1h)
        assertEquals(78, merged.batteryLevel)
    }
}
