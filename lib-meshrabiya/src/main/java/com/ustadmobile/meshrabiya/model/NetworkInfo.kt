package com.ustadmobile.meshrabiya.model

/**
 * Represents information about the current mesh network state.
 */
data class NetworkInfo(
    val ssid: String = "",
    val bssid: String = "",
    val ipAddress: String = "",
    val connectedPeers: Int = 0,
    val isConnected: Boolean = false
)