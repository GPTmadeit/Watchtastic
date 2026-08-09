package com.watchtastic.mesh

import com.watchtastic.mesh.model.MeshChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The primary channel travels with an empty name and takes its label from the modem
 * preset. Hardcoding "LongFast" mislabelled every mesh on a different preset — a real
 * bug reported from the field — so these lock the behaviour down.
 */
class ChannelNamingTest {

    private fun primary(name: String = "") =
        MeshChannel(index = 0, name = name, role = "PRIMARY", hasKey = true)

    @Test
    fun `unnamed primary channel takes its name from the modem preset`() {
        assertEquals("MediumFast", primary().resolveName("MEDIUM_FAST"))
        assertEquals("LongFast", primary().resolveName("LONG_FAST"))
        assertEquals("ShortTurbo", primary().resolveName("SHORT_TURBO"))
        assertEquals("VeryLongSlow", primary().resolveName("VERY_LONG_SLOW"))
    }

    @Test
    fun `an explicitly named channel keeps its own name whatever the preset`() {
        assertEquals("Ops", primary(name = "Ops").resolveName("MEDIUM_FAST"))
    }

    @Test
    fun `secondary channels fall back to their slot number, not the preset`() {
        val secondary = MeshChannel(index = 3, name = "", role = "SECONDARY", hasKey = false)
        assertEquals("Channel 3", secondary.resolveName("MEDIUM_FAST"))
    }

    @Test
    fun `an unknown or empty preset still produces something usable`() {
        // A radio that hasn't sent LoRa config yet leaves the preset blank; the row must
        // not render as an empty string.
        assertEquals("Primary", primary().resolveName(""))
    }

    @Test
    fun `disabled channels are not treated as enabled`() {
        val disabled = MeshChannel(index = 1, name = "", role = "DISABLED", hasKey = false)
        assertEquals(false, disabled.isEnabled)
        assertEquals(true, primary().isEnabled)
    }
}
