package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forwards CLEARNET-routed VirtualPackets to the internet via [Network.bindSocket].
 * Uses the provided [Network] object (the internet WiFi interface) so sockets bypass the
 * active Orbot VPN tunnel. UDP-only in Phase 1 (covers DNS, NTP, QUIC/HTTP3).
 */
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val onResponsePacket: (VirtualPacket) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun forwardViaTun(packet: VirtualPacket) {
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

                logger(Log.DEBUG, "$logPrefix forwardViaTun: dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                val socket = DatagramSocket()
                socket.soTimeout = 5_000
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(destInetAddr, destPort)))

                val responseBuffer = ByteArray(65_535)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                    val responseData = responsePacket.data.copyOf(responsePacket.length)
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
                    logger(Log.DEBUG, "$logPrefix response ${responsePacket.length} bytes → ${header.fromAddr}")
                } catch (e: java.net.SocketTimeoutException) {
                    logger(Log.WARN, "$logPrefix response timeout for $destInetAddr:$destPort")
                } finally {
                    socket.close()
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
        logger(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }
}
