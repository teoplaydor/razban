package com.razban.app.direct

/**
 * Distinguishes local TUN startup from an authenticated, TLS-working data
 * channel. The public UI word "connected" is reserved for [DataWorking].
 */
enum class DirectChannelState {
    Idle,
    TunUp,
    Signaling,
    TcpConnected,
    DataWorking,
    Error,
}

object VpnUiState {
    fun publicLabel(tunnelName: String, channel: DirectChannelState, directActive: Boolean): String {
        return when (tunnelName) {
            "Stopped" -> "disconnected"
            "Starting" -> "connecting"
            "Stopping" -> "disconnecting"
            "Started" -> if (!directActive || channel == DirectChannelState.DataWorking) {
                "connected"
            } else {
                "connecting"
            }
            else -> "disconnected"
        }
    }

    fun notifTitle(tunnelName: String, channel: DirectChannelState, directActive: Boolean): String {
        if (tunnelName != "Started") {
            return when (tunnelName) {
                "Starting" -> "Подключение"
                "Stopping" -> "Отключение"
                else -> "Отключено"
            }
        }
        if (!directActive) return "Подключено"
        return when (channel) {
            DirectChannelState.DataWorking -> "Подключено"
            DirectChannelState.Error -> "Канал: ошибка"
            DirectChannelState.TcpConnected -> "Канал: TCP"
            DirectChannelState.Signaling -> "Канал: сигнализация"
            DirectChannelState.TunUp -> "TUN запущен"
            DirectChannelState.Idle -> "TUN запущен"
        }
    }
}
