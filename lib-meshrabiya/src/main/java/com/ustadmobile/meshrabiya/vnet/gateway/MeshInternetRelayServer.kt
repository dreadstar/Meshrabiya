package com.ustadmobile.meshrabiya.vnet.gateway

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP relay server running on CLEARNET_GATEWAY nodes.
 *
 * Activated by [EmergentRoleManager] when the CLEARNET_GATEWAY role is assigned.
 * Accepts inbound ChainSocket TCP connections (from [MeshLocalSocksProxy] on client nodes).
 *
 * Protocol for each accepted connection:
 *  1. Read a 6-byte relay header: [4 bytes IPv4 dest addr][2 bytes dest port, big-endian]
 *  2. Open a real internet TCP socket to that address, bound to [internetNetwork] so that
 *     it uses the internet WiFi NIC directly and bypasses Orbot's VPN tunnel.
 *  3. Write a 1-byte ACK (0x00 = success, 0x01 = failure).
 *  4. Relay bytes bidirectionally between the client socket and the internet socket.
 *
 * Listens on all interfaces at [MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT] (9080).
 */
class MeshInternetRelayServer(
    private val logger: MNetLogger,
    private val logPrefix: String,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var internetNetwork: Network? = null

    /** Start the relay server. Idempotent. Call with the current internet-capable Network. */
    fun start(network: Network?) {
        if (serverSocket != null) return
        internetNetwork = network
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT))
        serverSocket = ss
        logger(Log.INFO, "$logPrefix MeshInternetRelayServer started on port ${MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT}")
        scope.launch {
            try {
                while (true) {
                    val client = ss.accept()
                    launch { handleClient(client) }
                }
            } catch (e: IOException) {
                logger(Log.INFO, "$logPrefix MeshInternetRelayServer stopped: ${e.message}")
            }
        }
    }

    /** Stop accepting connections. Idempotent. */
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        logger(Log.INFO, "$logPrefix MeshInternetRelayServer stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // Read 6-byte relay header
            val header = ByteArray(6)
            var offset = 0
            while (offset < 6) {
                val n = input.read(header, offset, 6 - offset)
                if (n < 0) { client.close(); return }
                offset += n
            }
            val destAddr = InetAddress.getByAddress(header.copyOfRange(0, 4))
            val destPort = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

            logger(Log.DEBUG, "$logPrefix relay CONNECT → ${destAddr.hostAddress}:$destPort")

            val internetSocket = Socket()
            try {
                val network = internetNetwork
                if (network != null) {
                    network.bindSocket(internetSocket)
                }
                internetSocket.connect(InetSocketAddress(destAddr, destPort), 10_000)
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix relay failed to connect ${destAddr.hostAddress}:$destPort — ${e.message}")
                output.write(0x01) // failure ACK
                output.flush()
                internetSocket.close(); client.close(); return
            }

            // Send success ACK
            output.write(0x00)
            output.flush()

            // Bidirectional relay
            val upThread = Thread {
                try { input.copyTo(internetSocket.outputStream) } catch (_: Exception) {}
                finally { try { internetSocket.close() } catch (_: Exception) {} }
            }
            upThread.isDaemon = true
            upThread.start()
            try { internetSocket.inputStream.copyTo(output) } catch (_: Exception) {}
            finally {
                try { client.close() } catch (_: Exception) {}
                try { internetSocket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix relay handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        stop()
        job.cancel()
    }
}
