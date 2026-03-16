package com.ustadmobile.meshrabiya.vnet

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
import javax.net.SocketFactory

/**
 * Local SOCKS5 proxy server (client-side mesh proxy).
 *
 * Listens on a loopback TCP port (dynamically assigned). When OrbotVpnManager routes
 * mesh-proxy-app traffic here via go-tun2socks, this proxy:
 *  1. Completes the SOCKS5 handshake with the app's TCP connection.
 *  2. Resolves the best available CLEARNET_GATEWAY virtual address from the mesh topology.
 *  3. Opens a ChainSocket TCP connection to that gateway's MeshInternetRelayServer
 *     at [MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT] using [meshSocketFactory].
 *  4. Sends a 6-byte relay header [4-byte IPv4 dest addr][2-byte dest port big-endian]
 *     and then relays bytes bidirectionally.
 *
 * If no CLEARNET_GATEWAY is reachable, replies with SOCKS5 "host unreachable" (0x04).
 *
 * @param logger             Mesh logger.
 * @param logPrefix          Log tag prefix.
 * @param meshSocketFactory  [VirtualNode.socketFactory] — routes TCP through mesh via ChainSocket.
 * @param getGatewayAddress  Lambda returning the virtual [InetAddress] of the best
 *                           CLEARNET_GATEWAY node, or null if none is available.
 */
class MeshLocalSocksProxy(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val meshSocketFactory: SocketFactory,
    private val getGatewayAddress: () -> InetAddress?,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var serverSocket: ServerSocket? = null

    /** The loopback TCP port this proxy is listening on. 0 if not started. */
    val localPort: Int get() = serverSocket?.localPort ?: 0

    /** Start accepting connections. Idempotent — safe to call multiple times. */
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        serverSocket = ss
        logger(Log.INFO, "$logPrefix MeshLocalSocksProxy started on port ${ss.localPort}")
        scope.launch {
            try {
                while (true) {
                    val client = ss.accept()
                    launch { handleSocks5(client) }
                }
            } catch (e: IOException) {
                logger(Log.INFO, "$logPrefix MeshLocalSocksProxy stopped: ${e.message}")
            }
        }
    }

    /** Stop accepting new connections. Idempotent. */
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleSocks5(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // --- SOCKS5 greeting ---
            val verBuf = ByteArray(2)
            input.readFully(verBuf)
            if (verBuf[0] != 0x05.toByte()) { client.close(); return }
            val nMethods = verBuf[1].toInt() and 0xFF
            if (nMethods > 0) input.readFully(ByteArray(nMethods))
            output.write(byteArrayOf(0x05, 0x00)) // server selects NO_AUTH
            output.flush()

            // --- SOCKS5 CONNECT request ---
            val reqBase = ByteArray(4)
            input.readFully(reqBase)
            if (reqBase[0] != 0x05.toByte() || reqBase[1] != 0x01.toByte()) {
                // Only CONNECT (0x01) supported
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }
            val atyp = reqBase[3].toInt() and 0xFF
            val destAddr: InetAddress = when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len); input.readFully(domain)
                    InetAddress.getByName(String(domain))
                }
                0x04 -> { // IPv6
                    val ip = ByteArray(16); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                else -> { client.close(); return }
            }
            val portBytes = ByteArray(2); input.readFully(portBytes)
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // --- Resolve gateway ---
            val gatewayAddr = getGatewayAddress()
            if (gatewayAddr == null) {
                logger(Log.WARN, "$logPrefix No CLEARNET_GATEWAY available for ${destAddr.hostAddress}:$destPort")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // host unreachable
                client.close(); return
            }

            logger(Log.DEBUG, "$logPrefix → ${destAddr.hostAddress}:$destPort via gateway ${gatewayAddr.hostAddress}")

            // --- Open ChainSocket to gateway's relay server ---
            val meshSocket: Socket = meshSocketFactory.createSocket(
                gatewayAddr, MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT
            )

            // Send 6-byte relay header: [4-byte dest IPv4][2-byte dest port]
            val destIpBytes = destAddr.address // 4 bytes for IPv4 (resolved above)
            val relayHeader = byteArrayOf(
                destIpBytes[0], destIpBytes[1], destIpBytes[2], destIpBytes[3],
                ((destPort shr 8) and 0xFF).toByte(),
                (destPort and 0xFF).toByte()
            )
            meshSocket.getOutputStream().write(relayHeader)
            meshSocket.getOutputStream().flush()

            // Read 1-byte ACK from relay server (0x00 = success)
            val ack = meshSocket.getInputStream().read()
            if (ack != 0x00) {
                logger(Log.WARN, "$logPrefix Relay server rejected ${destAddr.hostAddress}:$destPort ack=$ack")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                meshSocket.close(); client.close(); return
            }

            // --- Send SOCKS5 success reply to client ---
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // --- Bidirectional relay ---
            val upThread = Thread {
                try { input.copyTo(meshSocket.outputStream) } catch (_: Exception) {}
                finally { try { meshSocket.close() } catch (_: Exception) {} }
            }
            upThread.isDaemon = true
            upThread.start()
            try { meshSocket.inputStream.copyTo(output) } catch (_: Exception) {}
            finally {
                try { client.close() } catch (_: Exception) {}
                try { meshSocket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix SOCKS5 handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        stop()
        job.cancel()
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected EOF (read ${offset}/${buf.size} bytes)")
            offset += n
        }
    }
}
