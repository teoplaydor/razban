package com.razban.app.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnUiStateTest {
    @Test
    fun directTunUpIsNotConnected() {
        assertEquals(
            "connecting",
            VpnUiState.publicLabel("Started", DirectChannelState.TunUp, true),
        )
        assertEquals(
            "connecting",
            VpnUiState.publicLabel("Started", DirectChannelState.TcpConnected, true),
        )
        assertEquals(
            "connecting",
            VpnUiState.publicLabel("Started", DirectChannelState.Signaling, true),
        )
        assertEquals(
            "connecting",
            VpnUiState.publicLabel("Started", DirectChannelState.Error, true),
        )
    }

    @Test
    fun directDataWorkingIsConnected() {
        assertEquals(
            "connected",
            VpnUiState.publicLabel("Started", DirectChannelState.DataWorking, true),
        )
    }

    @Test
    fun relayStartedIsConnected() {
        assertEquals(
            "connected",
            VpnUiState.publicLabel("Started", DirectChannelState.Idle, false),
        )
    }

    @Test
    fun notifTitleDoesNotClaimConnectedBeforeData() {
        assertEquals("TUN запущен", VpnUiState.notifTitle("Started", DirectChannelState.TunUp, true))
        assertEquals("Канал: TCP", VpnUiState.notifTitle("Started", DirectChannelState.TcpConnected, true))
        assertEquals("Подключено", VpnUiState.notifTitle("Started", DirectChannelState.DataWorking, true))
        assertEquals("Подключено", VpnUiState.notifTitle("Started", DirectChannelState.Idle, false))
    }
}
