package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Forwards CLEARNET-routed VirtualPackets to the internet via the provided Network object.
 *
 * The [internetWifiNetwork] is the Network obtained from ConnectivityManager when
 * connectToInternetWifi() succeeds. Sockets created via its socketFactory are automatically
 * routed through the internet WiFi interface, bypassing the mesh.
 *
 * Usage: instantiate one instance; call [forward] when GATEWAY_TYPE_CLEARNET packets arrive.
 * Call [close] when the internet WiFi connection is lost to release resources.
 *
 * NOTE: This is a functional skeleton. The full implementation requires:
 * - Parsing the VirtualPacket IP payload to extract destination IP + port
 * - Creating a TCP or UDP socket via internetWifiNetwork.socketFactory
 * - Binding the socket to the network via internetWifiNetwork.bindSocket(socket) for UDP
 * - Streaming the payload, collecting the response, and injecting it back into the mesh
 *
 * The ChainSocketServer pattern (already used in MeshrabiyaWifiManagerAndroid for station
 * bound sockets) is the recommended approach for the forwarding implementation.
 */
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Forward a CLEARNET-tagged VirtualPacket via the provided internet WiFi network.
     * @param packet The VirtualPacket with gatewayType == GATEWAY_TYPE_CLEARNET.
     * @param internetWifiNetwork The Network object for the internet WiFi connection.
     */
    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                // TODO: Parse VirtualPacket IP payload, extract destination address and port.
                // TODO: Create socket via internetWifiNetwork.socketFactory.
                // TODO: internetWifiNetwork.bindSocket(socket) for UDP sockets.
                // TODO: Write payload, read response, inject back into virtual network.
                logger(Log.DEBUG, "$logPrefix ClearnetGatewayForwarder: forward() — implementation pending")
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix ClearnetGatewayForwarder: forward error: ${e.message}")
            }
        }
    }

    fun close() {
        // Cancel scope and release resources when internet WiFi disconnects.
        // scope.cancel() can be added when a SupervisorJob is provided.
        logger(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }
}
