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
        // (This test used to exercise `copy()`, which can't fail — while the store's real
        // hand-written merge dropped both lightning fields on every packet.)
        val environment = NodeMetrics(temperature = 11.5f, lightningStrikes1h = 3)
        val device = NodeMetrics(batteryLevel = 78, voltage = 3.9f)
        val merged = device.mergedOver(environment)
        assertEquals(11.5f, merged.temperature)
        assertEquals(3, merged.lightningStrikes1h)
        assertEquals(78, merged.batteryLevel)
        assertEquals(3.9f, merged.voltage)
    }

    @Test
    fun `lightning survives being merged into a node that had none`() {
        val incoming = NodeMetrics(lightningStrikes1h = 5, lightningDistanceKm = 7.5f)
        val merged = incoming.mergedOver(NodeMetrics(batteryLevel = 90))
        assertEquals(5, merged.lightningStrikes1h)
        assertEquals(7.5f, merged.lightningDistanceKm)
        assertTrue(merged.hasLightning)
    }

    @Test
    fun `newer readings win over older ones`() {
        val merged = NodeMetrics(lightningStrikes1h = 0, batteryLevel = 40)
            .mergedOver(NodeMetrics(lightningStrikes1h = 9, batteryLevel = 80))
        assertEquals(0, merged.lightningStrikes1h)
        assertEquals(40, merged.batteryLevel)
    }

    @Test
    fun `merge carries every field`() {
        // Guards the class of bug, not just the instance: when a field is added to
        // NodeMetrics, a merge that forgets it fails here.
        val full = NodeMetrics(
            batteryLevel = 1, voltage = 2f, channelUtilization = 3f, airUtilTx = 4f,
            uptimeSeconds = 5, temperature = 6f, relativeHumidity = 7f,
            barometricPressure = 8f, lightningStrikes1h = 9, lightningDistanceKm = 10f,
        )
        assertEquals(full, NodeMetrics().mergedOver(full))
        assertEquals(full, full.mergedOver(NodeMetrics()))
    }
}
