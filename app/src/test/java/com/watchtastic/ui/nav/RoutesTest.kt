package com.watchtastic.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoutesTest {

    @Test
    fun `top-level screens resolve to themselves`() {
        assertEquals(Routes.HOME, Routes.forDeepLink("home"))
        assertEquals(Routes.NODES, Routes.forDeepLink("nodes"))
        assertEquals(Routes.CONVERSATIONS, Routes.forDeepLink("conversations"))
    }

    @Test
    fun `well-formed conversation keys are accepted`() {
        assertNotNull(Routes.forDeepLink("ch:0"))
        assertNotNull(Routes.forDeepLink("ch:7"))
        assertNotNull(Routes.forDeepLink("dm:123456"))
        assertNotNull(Routes.forDeepLink("dm:-5"))
    }

    @Test
    fun `anything else is rejected`() {
        listOf(
            "", "settings", "radio", "update", "chat/ch:0", "node/5",
            "ch:8", "ch:-1", "ch:", "ch:abc", "dm:", "dm:xyz", "dm:1/../update", "../x",
        ).forEach { assertNull("'$it' should be rejected", Routes.forDeepLink(it)) }
    }
}
