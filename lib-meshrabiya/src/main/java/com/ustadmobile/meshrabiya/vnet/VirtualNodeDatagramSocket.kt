package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 *
 * VirtualNodeDatagramSocket listens on the real network interface. It uses the executor service
 * to run a thread that will receive all packets, convert them from a DatagramPacket into a
 * VirtualPacket, and then give them to the VirtualRouter.
 *
 * @param socket - the underlying DatagramSocket to use - this can be bound to a network, interface etc if required
 * neighbor connects.
 * @param boundNetwork The Network object that the DatagramSocket is/will be bound to, if any. This
 *                     is needed if/when we want to establish a TCP connection. Because the
 *                     VirtualNodeDatagramSocket reference is kept as part of the originator message,
 *                     and it is created at the time the network object is available, this is the
 *                     most convenient and logical place to keep this reference.
 */
class VirtualNodeDatagramSocket(
    private val socket: DatagramSocket,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualNode,
    private val logger: MNetLogger,
    name: String? = null,
    val boundNetwork: Network? = null,
):  Runnable, Closeable {

    private val future: Future<*>

    private val logPrefix: String

    val localPort: Int = socket.localPort

    init {
        logPrefix = buildString {
            append("[VirtualNodeDatagramSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
        logger(Log.DEBUG, "$logPrefix Started on ${socket.localPort} waiting for first packet", null)

        while(!Thread.interrupted() && !socket.isClosed) {
            try {
                val rxPacket = DatagramPacket(buffer, 0, buffer.size)
                socket.receive(rxPacket)

                logger(Log.INFO, "$logPrefix ⬇️ RECEIVED packet from ${rxPacket.address}:${rxPacket.port} size=${rxPacket.length} bytes", null)
                logger(Log.INFO, "$logPrefix [PKT_RX_RAW] src=${rxPacket.address?.hostAddress}:${rxPacket.port} dst=${socket.localAddress?.hostAddress}:${socket.localPort} boundNetwork=$boundNetwork len=${rxPacket.length} payload=${rxPacket.data.copyOfRange(rxPacket.offset, rxPacket.offset + rxPacket.length).joinToString("") { "%02x".format(it) }}")

                val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)
                logger(Log.INFO, "$logPrefix 📦 Packet details: from=${rxVirtualPacket.header.fromAddr.addressToDotNotation()}:${rxVirtualPacket.header.fromPort} to=${rxVirtualPacket.header.toAddr.addressToDotNotation()}:${rxVirtualPacket.header.toPort} hopCount=${rxVirtualPacket.header.hopCount} payloadSize=${rxVirtualPacket.header.payloadSize}", null)

                // --- DEEP PACKET INSPECTION LOGGING ---
                try {
                    val payload = rxVirtualPacket.data.copyOfRange(rxVirtualPacket.payloadOffset, rxVirtualPacket.payloadOffset + rxVirtualPacket.header.payloadSize)
                    if (payload.isNotEmpty()) {
                        val packetType = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer.getPacketType(payload)
                        if (packetType == com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK) {
                            val (broadcastId, _, chunkPair) = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer.deserialize(payload)
                            val chunkMeta = chunkPair.first
                            logger(Log.INFO, "$logPrefix [DEEP_INSPECT] BROADCAST_CHUNK: broadcastId=$broadcastId chunkId=${chunkMeta.chunkId} chunkIndex=${chunkMeta.chunkIndex} totalChunks=${chunkMeta.totalChunks} fileId=${chunkMeta.fileId} from=${rxVirtualPacket.header.fromAddr.addressToDotNotation()}:${rxVirtualPacket.header.fromPort} to=${rxVirtualPacket.header.toAddr.addressToDotNotation()}:${rxVirtualPacket.header.toPort} hopCount=${rxVirtualPacket.header.hopCount} stateVars={routerState=${router.currentNodeState}}", null)
                        }
                    }
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix [DEEP_INSPECT] Failed to parse broadcast chunk for logging: ${e.message}", e)
                }
                // --- END DEEP PACKET INSPECTION LOGGING ---

                router.incrementDownloadBytes(rxPacket.length.toLong())
                
                router.route(
                    packet = rxVirtualPacket,
                    datagramPacket = rxPacket,
                    virtualNodeDatagramSocket = this,
                )
            }catch(e: Exception) {
                if(!socket.isClosed)
                    logger(Log.WARN, "$logPrefix : run : exception handling packet", e)
            }
        }
        logger(Log.DEBUG, "$logPrefix : run : finished")
    }


    /**
     *
     */
    fun send(
        nextHopAddress: InetAddress,
        nextHopPort: Int,
        virtualPacket: VirtualPacket,
        sourceLabel: String = "VirtualNodeDatagramSocket"
    ) {
        val datagramPacket = virtualPacket.toDatagramPacket()
        datagramPacket.address = nextHopAddress
        datagramPacket.port = nextHopPort
        
        logger(Log.INFO,
            "$logPrefix ⬆️ SENDING packet to ${nextHopAddress}:${nextHopPort} " +
            "source=$sourceLabel " +
            "len=${datagramPacket.length} local=${socket.localAddress}:${socket.localPort} " +
            "boundNetwork=${boundNetwork ?: "none"}",
            null
        )

        socket.send(datagramPacket)

        logger(Log.DEBUG,
            "$logPrefix ✅ Packet sent successfully to ${nextHopAddress}:${nextHopPort} " +
            "local=${socket.localAddress}:${socket.localPort} " +
            "source=$sourceLabel",
            null
        )
        router.incrementUploadBytes(datagramPacket.length.toLong())
    }

    fun close(closeSocket: Boolean) {
        future.cancel(true)
        socket.takeIf { closeSocket }?.close()
    }

    override fun close() {
        close(false)
    }
}
