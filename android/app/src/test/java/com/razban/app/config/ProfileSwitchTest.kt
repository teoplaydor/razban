package com.razban.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSwitchTest {
    @Test
    fun liveTunnelBlocksSwitch() {
        assertTrue(ProfileSwitch.blockedByTunnel("Started"))
        assertTrue(ProfileSwitch.blockedByTunnel("Starting"))
        assertFalse(ProfileSwitch.blockedByTunnel("Stopped"))
        assertFalse(ProfileSwitch.blockedByTunnel("Stopping"))
        assertFalse(ProfileSwitch.blockedByTunnel(""))
        assertEquals("disconnect_required", ProfileSwitch.DISCONNECT_REQUIRED)
    }
}
