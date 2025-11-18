package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.service.MeshGossipService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * CoreGossipBroadcastService: Handles serialization and UDP broadcast of ecosystem messages.
 * 
 * Architecture:
 * - Accepts MeshGossipService via constructor (dependency injection)
 * - Serializes MeshEcosystemMessage → MessagePack bytes
 * - Delegates UDP broadcast to MeshGossipService.broadcastMessage()
 * - Tracks broadcast IDs to prevent message loops
 * 
 * Dependencies:
 * - MeshGossipService (injected, for UDP broadcast)
 * - MeshEcosystemMessage (for message format)
 * 
 * Used By:
 * - DistributedStorageManager (sends storage broadcasts)
 * - IntelligentDistributedComputeService (sends compute broadcasts)
 */
class CoreGossipBroadcastService(
    private val meshGossipService: MeshGossipService,
    private val broadcastTtlMs: Long = 60_000L
) {

    private val seenBroadcasts = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var isShutdown = false

    init {
        // Background task to clean up expired broadcast IDs
        scope.launch {
            while (isActive && !isShutdown) {
                val cutoff = System.currentTimeMillis() - broadcastTtlMs
                seenBroadcasts.entries.removeIf { it.value < cutoff }
                delay(broadcastTtlMs / 2)
            }
        }
    }

    /**
     * Broadcast a MeshEcosystemMessage to the mesh network.
     * 
     * This is called by domain managers (DistributedStorageManager, ComputeService) after they
     * prepare their domain-specific messages. This service handles:
     * - Message serialization (toBytes())
     * - Deduplication ID generation
     * - Delegating actual UDP broadcast to MeshGossipService
     * 
     * Deduplication: Uses message type + SHA-256 hash of payload to prevent duplicate sends.
     * 
     * @param message The prepared MeshEcosystemMessage to broadcast
     * @param broadcastId Optional custom broadcast ID (default: auto-generated from message)
     */
    fun sendBroadcast(message: MeshEcosystemMessage, broadcastId: String? = null) {
        if (isShutdown) {
            return
        }
        
        val payload = message.toBytes()
        val id = broadcastId ?: computeBroadcastId(message.type, payload)
        
        // Check if we've already sent this exact message
        val prev = seenBroadcasts.putIfAbsent(id, System.currentTimeMillis())
        if (prev != null) {
            // Already sent this message - skip to prevent duplicate
            return
        }
        
        // Delegate actual UDP broadcast to MeshGossipService
        meshGossipService.broadcastMessage(payload)
    }

    /**
     * Send a storage node request broadcast.
     * Called by DistributedStorageManager when looking for available storage nodes.
     * 
     * @param request The storage node request details (includes requestId)
     */
    fun sendStorageNodeRequest(request: StorageNodeRequest) {
        val message = MeshEcosystemMessage.StorageNodeRequestMessage(request)
        sendBroadcast(message)
    }

    /**
     * Send a chunk retrieval query broadcast.
     * Called by DistributedStorageManager when looking for nodes that have a specific file chunk.
     * 
     * @param fileId The file ID to query for
     */
    fun sendChunkRetrievalQuery(fileId: String) {
        val query = ChunkRetrievalQuery(fileId)
        val message = MeshEcosystemMessage.ChunkRetrievalQueryMessage(query)
        sendBroadcast(message)
    }

    /**
     * Send a replica query broadcast.
     * Called by DistributedStorageManager to find all nodes storing replicas of a file.
     * 
     * @param fileId The file ID to query replicas for
     */
    fun sendReplicaQuery(fileId: String) {
        val query = ReplicaQuery(fileId)
        val message = MeshEcosystemMessage.ReplicaQueryMessage(query)
        sendBroadcast(message)
    }

    // DEPRECATED: Storage advertisement superseded by OriginatorMessage with MeshRole.STORAGE
    // /**
    //  * Send a storage capabilities advertisement.
    //  * Called by DistributedStorageManager to advertise this node's storage availability.
    //  * 
    //  * @param capabilities The storage capabilities to advertise
    //  */
    // fun sendStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.storage.StorageCapabilities) {
    //     val message = MeshEcosystemMessage.StorageCapabilitiesMessage(capabilities)
    //     sendBroadcast(message)
    // }

    /**
     * Send a compute task request broadcast.
     * Called by IntelligentDistributedComputeService when looking for compute nodes.
     * 
     * @param taskId Unique task identifier
     * @param serviceId Service identifier for the task
     * @param inputParams Task input parameters
     * @param metadata Task metadata (timeout, priority, etc.)
     */
    fun sendComputeTaskRequest(
        taskId: String,
        serviceId: String,
        inputParams: Map<String, Any>,
        metadata: Map<String, String>
    ) {
        val message = MeshEcosystemMessage.ComputeTaskRequestMessage(
            taskId = taskId,
            serviceId = serviceId,
            inputParams = inputParams,
            metadata = metadata
        )
        sendBroadcast(message)
    }

    /**
     * Send a file permission update broadcast.
     * Called by DistributedStorageManager when file access permissions change.
     * 
     * @param message The permission update message
     */
    fun sendFilePermissionUpdate(message: MeshEcosystemMessage.FilePermissionUpdateMessage) {
        sendBroadcast(message)
    }

    /**
     * Send a task data access update broadcast.
     * Called by IntelligentDistributedComputeService when task data access rights change.
     * 
     * @param message The task data access update message
     */
    fun sendTaskDataAccessUpdate(message: MeshEcosystemMessage.TaskDataAccessUpdateMessage) {
        sendBroadcast(message)
    }

    /**
     * Send a generic ecosystem broadcast.
     * For custom ecosystem-wide messages not covered by specific types.
     * 
     * @param broadcastId Unique broadcast identifier
     * @param senderId Node ID of sender
     * @param messageType Type identifier for the message
     * @param payload Raw message payload
     */
    fun sendEcosystemBroadcast(
        broadcastId: String,
        senderId: String,
        messageType: String,
        payload: ByteArray
    ) {
        val message = MeshEcosystemMessage.EcosystemBroadcastMessage(
            broadcastId = broadcastId,
            senderId = senderId,
            messageType = messageType,
            payload = payload
        )
        sendBroadcast(message)
    }

    /**
     * Compute a unique broadcast ID for deduplication.
     * Uses messageType + SHA-256 hash of message bytes.
     * 
     * This ensures that:
     * - Same message sent multiple times = same ID (deduplicated)
     * - Different messages with same type = different IDs (both sent)
     */
    private fun computeBroadcastId(messageType: String, messageBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(messageBytes)
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "$messageType:$hex"
    }

    /**
     * Get diagnostic statistics (useful for monitoring/debugging).
     */
    fun getStats(): BroadcastStats {
        return BroadcastStats(
            seenBroadcastCount = seenBroadcasts.size,
            isShutdown = isShutdown
        )
    }

    /**
     * Diagnostic statistics for monitoring/debugging.
     */
    data class BroadcastStats(
        val seenBroadcastCount: Int,
        val isShutdown: Boolean
    )

    /**
     * Shutdown the broadcast service.
     * Cancels background cleanup task and clears all state.
     */
    fun shutdown() {
        if (isShutdown) {
            return
        }
        
        isShutdown = true
        scope.cancel()
        seenBroadcasts.clear()
    }
    
    /**
     * Check if service is shutdown.
     */
    fun isShutdown(): Boolean = isShutdown
}