package com.watchtastic.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison decides whether a watch installs an APK, so a mistake here is the
 * difference between a silent no-op and a device being offered a downgrade.
 */
class VersionTest {

    @Test
    fun `parses plain and v-prefixed versions`() {
        assertEquals(listOf(1, 4, 1), Version.parse("1.4.1")?.parts)
        assertEquals(listOf(1, 4, 1), Version.parse("v1.4.1")?.parts)
    }

    @Test
    fun `rejects text with no version in it`() {
        assertNull(Version.parse("nightly"))
        assertNull(Version.parse(""))
    }

    @Test
    fun `orders by numeric component, not lexically`() {
        val v190 = Version.parse("1.9.0")!!
        val v1100 = Version.parse("1.10.0")!!
        // Lexically "1.10.0" < "1.9.0"; numerically it must be greater.
        assertTrue(v1100 > v190)
    }

    @Test
    fun `differing component counts compare sensibly`() {
        val short = Version.parse("1.4")!!
        val long = Version.parse("1.4.1")!!
        assertTrue(long > short)
        assertEquals(0, Version.parse("1.4.0")!!.compareTo(Version.parse("1.4.0")!!))
    }

    @Test
    fun `pulls the version out of a release asset filename`() {
        val version = Version.fromFileName("Watchtastic-1.4.1-release.apk")
        assertEquals(listOf(1, 4, 1), version?.parts)
    }

    @Test
    fun `a filename with no version yields null rather than a wrong guess`() {
        assertNull(Version.fromFileName("watchtastic-release.apk"))
    }

    @Test
    fun `the current build is never newer than itself`() {
        val a = Version.parse("1.4.1")!!
        val b = Version.parse("1.4.1")!!
        assertTrue(!(a > b) && !(b > a))
    }
}
