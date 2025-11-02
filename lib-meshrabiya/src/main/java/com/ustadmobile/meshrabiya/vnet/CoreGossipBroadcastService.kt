package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Core gossip broadcast service for mesh-wide ecosystem messages.
 * Handles deduplication, serialization, deserialization, and listener routing for MeshEcosystemMessage.
 */
class CoreGossipBroadcastService(
    private val virtualNode: VirtualNode,
    private val broadcastTtlMs: Long = 60_000L
) {

    private val seenBroadcasts = ConcurrentHashMap<String, Long>()
    private val listeners = ConcurrentHashMap<String, MutableList<(Int, MeshEcosystemMessage) -> Unit>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                val cutoff = System.currentTimeMillis() - broadcastTtlMs
                seenBroadcasts.entries.removeIf { it.value < cutoff }
                delay(broadcastTtlMs / 2)
            }
        }
    }

    /**
     * Broadcast a MeshEcosystemMessage to the mesh using UDP.
     * Deduplicates by message type and payload.
     */
    fun sendBroadcast(message: MeshEcosystemMessage, broadcastId: String? = null) {
        val payload = message.toBytes()
        val id = broadcastId ?: computeBroadcastId(message.type, payload)
        seenBroadcasts[id] = System.currentTimeMillis()
        val port = MeshrabiyaConstants.getEcosystemGossipPort()
        val socket = virtualNode.createBoundDatagramSocket(port)
        val packet = DatagramPacket(
            payload,
            payload.size,
            InetAddress.getByName("255.255.255.255"),
            port
        )
        socket.send(packet)
        socket.close()
    }

    /**
     * Handle an incoming broadcast packet.
     * Deserializes to MeshEcosystemMessage and routes to registered listeners.
     */
    fun onReceiveBroadcast(rawBytes: ByteArray, senderId: Int = -1) {
        val message = try {
            MeshEcosystemMessage.fromBytes(rawBytes)
        } catch (e: Exception) {
            // Optionally log deserialization error
            return
        }
        val id = computeBroadcastId(message.type, rawBytes)
        val now = System.currentTimeMillis()
        val prev = seenBroadcasts.putIfAbsent(id, now)
        if (prev == null) {
            listeners[message.type]?.forEach { listener ->
                listener(senderId, message)
            }
        }
        // If already seen, drop for rebroadcast purposes
    }

    /**
     * Register a listener for a specific MeshEcosystemMessage type.
     */
    fun registerListener(messageType: String, listener: (Int, MeshEcosystemMessage) -> Unit) {
        listeners.computeIfAbsent(messageType) { mutableListOf() }.add(listener)
    }

    /**
     * Unregister a previously registered listener.
     */
    fun unregisterListener(messageType: String, listener: (Int, MeshEcosystemMessage) -> Unit) {
        listeners[messageType]?.remove(listener)
    }

    private fun computeBroadcastId(messageType: String, messageBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(messageBytes)
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "$messageType:$hex"
    }

    fun shutdown() {
        scope.cancel()
        seenBroadcasts.clear()
        listeners.clear()
    }
}