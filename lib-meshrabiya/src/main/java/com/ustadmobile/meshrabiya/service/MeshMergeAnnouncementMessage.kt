package com.ustadmobile.meshrabiya.service

import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker

/**
 * Message broadcast across mesh to announce merge to new mesh configuration.
 * 
 * Unlike coordinator-based approaches, this message does NOT require:
 * - Acknowledgment from all devices
 * - Quorum checking
 * - Synchronized execution timestamps
 * 
 * Each device independently decides whether to join based on idempotent config comparison.
 * 
 * @param targetSsid SSID pattern for target mesh (e.g., "meshr-*" for mesh-wide discovery)
 * @param targetPassword Shared password for target mesh (e.g., "meshtest12")
 * @param targetPort UDP port for mesh communication
 * @param announcerId Virtual address of device that initiated merge (for tracking only)
 * @param messageId UUID for deduplication
 * @param ttl Time-to-live (hops remaining before message dies) - decremented by each forwarder
 * @param hopCount Number of hops message has traveled (incremented by each forwarder)
 * @param timestamp Milliseconds since epoch when announcement was created
 */
data class MeshMergeAnnouncementMessage(
    val targetSsid: String,
    val targetPassword: String,
    val targetPort: Int,
    val announcerId: Int,
    val messageId: String,
    val ttl: Int = 5,  // Default: 5 hops
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) : MeshEcosystemMessage("MeshMergeAnnouncement") {
    
    override fun toBytes(): ByteArray {
        val packer: MessageBufferPacker = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(targetSsid)
        packer.packString(targetPassword)
        packer.packInt(targetPort)
        packer.packInt(announcerId)
        packer.packString(messageId)
        packer.packInt(ttl)
        packer.packInt(hopCount)
        packer.packLong(timestamp)
        return packer.toByteArray()
    }
}
