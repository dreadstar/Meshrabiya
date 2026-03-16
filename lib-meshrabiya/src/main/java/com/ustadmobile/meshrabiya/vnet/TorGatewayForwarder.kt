package com.ustadmobile.meshrabiya.vnet

import android.util.Log
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
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forwards TOR-routed VirtualPackets via Orbot's SOCKS5 proxy at 127.0.0.1:[orbotSocks5Port].
 * Loopback connections bypass the VPN tun0 interface on Android, so no special Network binding
 * is required — the OS routes 127.0.0.1 outside the VPN.
 */
class TorGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val orbotSocks5Port: Int = 9050,
    private val onResponsePacket: (VirtualPacket) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun forward(packet: VirtualPacket) {
        scope.launch {
            try {
                val header = packet.header
                val destIpBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(header.toAddr)
                    .array()
                val destInetAddr = InetAddress.getByAddress(destIpBytes)
                val destPort = header.toPort.toInt() and 0xFFFF
                val payloadSize = header.payloadSize
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset,
                    packet.payloadOffset + payloadSize
                )

                logger(Log.DEBUG, "$logPrefix forward: dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", orbotSocks5Port), 5_000)
                socket.soTimeout = 10_000
                val input: InputStream = socket.getInputStream()
                val output: OutputStream = socket.getOutputStream()

                // SOCKS5 greeting: version=5, nMethods=1, method=NO_AUTH
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                val serverGreet = ByteArray(2)
                input.read(serverGreet)
                if (serverGreet[0] != 0x05.toByte() || serverGreet[1] != 0x00.toByte()) {
                    logger(Log.WARN, "$logPrefix SOCKS5 handshake failed: ${serverGreet.toHex()}")
                    socket.close()
                    return@launch
                }

                // SOCKS5 CONNECT to IPv4 destination
                val connectReq = ByteBuffer.allocate(10)
                    .put(0x05)   // version
                    .put(0x01)   // CMD CONNECT
                    .put(0x00)   // reserved
                    .put(0x01)   // ATYP IPv4
                    .put(destIpBytes)
                    .putShort(destPort.toShort())
                    .array()
                output.write(connectReq)

                val connectReply = ByteArray(10)
                input.read(connectReply)
                if (connectReply[1] != 0x00.toByte()) {
                    logger(Log.WARN, "$logPrefix SOCKS5 REP=${connectReply[1]} for ${destInetAddr.hostAddress}:$destPort")
                    socket.close()
                    return@launch
                }

                output.write(payload)
                output.flush()

                val responseBuffer = ByteArray(65_535)
                val bytesRead = input.read(responseBuffer)
                socket.close()

                if (bytesRead > 0) {
                    val responseData = responseBuffer.copyOf(bytesRead)
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,
                        fromPort = destPort,
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    onResponsePacket(VirtualPacket.fromHeaderAndPayloadData(returnHeader, responseData, 0))
                    logger(Log.DEBUG, "$logPrefix response $bytesRead bytes → ${header.fromAddr}")
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix forward error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix unexpected error: ${e.message}", e)
            }
        }
    }

    fun close() {
        job.cancel()
        logger(Log.INFO, "$logPrefix TorGatewayForwarder: closed")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
