package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import org.torproject.android.service.compute.IntelligentDistributedComputeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
import com.ustadmobile.meshrabiya.storage.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse


/**
 * Listens for mesh ecosystem events: chunk transfer, broadcast responses, permission updates, etc.
 * Uses MeshConnectionPool for efficient communication for all chunk/file transfers.
 * Routes all mesh ecosystem events to registered handlers.
 */
class MeshEcosystemListener(
    private val meshNetworkInterface: MeshNetworkInterface,
    private val coreGossipBroadcastService: CoreGossipBroadcastService,
    connectionPoolSize: Int = MeshrabiyaConstants.getConnectionPoolSize()
) {

    private val connectionPool = MeshConnectionPool(meshNetworkInterface, poolSize = connectionPoolSize)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Registered handlers
    private var storageManager: DistributedStorageManager? = null
    private var computeService: IntelligentDistributedComputeService? = null

    private val isShutdown = AtomicBoolean(false)
    private var isStorageParticipationEnabled: Boolean = false

    // Controls whether storage node responsibilities are enabled
    var isStorageNodeEnabled: Boolean = true
        set(value) {
            field = value
        }

    init {
        registerBroadcastListeners()
    }

    fun registerStorageManager(manager: DistributedStorageManager) {
        storageManager = manager
    }

    fun registerComputeService(service: IntelligentDistributedComputeService) {
        computeService = service
    }

    fun setStorageParticipationEnabled(enabled: Boolean) {
        isStorageParticipationEnabled = enabled
    }

    // Register listeners for MeshEcosystemMessage types
    private fun registerBroadcastListeners() {
        coreGossipBroadcastService.registerListener("StorageNodeResponse") { senderId, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (isStorageParticipationEnabled && msg is MeshEcosystemMessage.StorageNodeResponseMessage) {
                routeStorageNodeResponse(senderId, msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("ChunkRetrievalResponse") { senderId, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (isStorageParticipationEnabled && msg is MeshEcosystemMessage.ChunkRetrievalResponseMessage) {
                routeChunkRetrievalResponse(senderId, msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("ReplicaResponse") { senderId, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (isStorageParticipationEnabled && msg is MeshEcosystemMessage.ReplicaResponseMessage) {
                routeReplicaResponse(senderId, msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("FilePermissionUpdateConfirmation") { _, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (isStorageParticipationEnabled && msg is MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage) {
                routePermissionUpdateConfirmation(msg)
            }
        }
        coreGossipBroadcastService.registerListener("TaskDataAccessUpdateMessage") { _, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (msg is MeshEcosystemMessage.TaskDataAccessUpdateMessage) {
                routeTaskDataAccessUpdate(msg)
            }
        }
        coreGossipBroadcastService.registerListener("ChunkTransfer") { senderId, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (isStorageParticipationEnabled && msg is MeshEcosystemMessage.ChunkTransferMessage) {
                onChunkTransfer(senderId, msg)
            }
        }
        coreGossipBroadcastService.registerListener("EcosystemBroadcast") { senderId, bytes, type, _ ->
            val msg = MeshEcosystemMessage.fromBytes(bytes)
            if (msg is MeshEcosystemMessage.EcosystemBroadcastMessage) {
                routeEcosystemBroadcast(senderId, msg)
            }
        }
    }

    // Centralized routing for broadcast and node-to-node events
    private fun routeStorageNodeResponse(senderId: Int, response: StorageNodeResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleStorageNodeResponse(senderId, response)
        }
    }

    private fun routeChunkRetrievalResponse(senderId: Int, response: ChunkRetrievalResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleChunkRetrievalResponse(senderId, response)
        }
    }

    private fun routeReplicaResponse(senderId: Int, response: ReplicaResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleReplicaResponse(senderId, response)
        }
    }

    private fun routePermissionUpdateConfirmation(confirmation: MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage) {
        if (isStorageNodeEnabled) {
            storageManager?.handlePermissionUpdateConfirmation(confirmation)
        }
    }

    private fun routeTaskDataAccessUpdate(updateMsg: MeshEcosystemMessage.TaskDataAccessUpdateMessage) {
        computeService?.handleTaskDataAccessUpdate(updateMsg)
    }

    /**
     * Handles inbound chunk/file transfer events.
     * Both sender and receiver must acquire/release connections from the pool.
     * Only processes if storage node responsibilities are enabled.
     */
    fun onChunkTransfer(senderId: Int, chunk: MeshEcosystemMessage.ChunkTransferMessage) {
        scope.launch {
            val connection = try {
                connectionPool.acquireConnection(timeoutMs = 5000)
            } catch (e: Exception) {
                null
            }
            if (connection != null) {
                try {
                    if (isStorageParticipationEnabled) {
                        storageManager?.handleIncomingChunkTransfer(senderId, chunk)
                    }
                } finally {
                    connectionPool.releaseConnection(connection)
                }
            }
        }
    }

    /**
     * Handles inbound ecosystem broadcast messages.
     */
    private fun routeEcosystemBroadcast(senderId: Int, broadcastMsg: MeshEcosystemMessage.EcosystemBroadcastMessage) {
        // Example: route based on broadcastMsg.messageType or payload
        // Extend this method to handle specific broadcast types as needed
        // For now, just log or pass to storageManager/computeService if relevant
        // storageManager?.handleEcosystemBroadcast(senderId, broadcastMsg)
        // computeService?.handleEcosystemBroadcast(senderId, broadcastMsg)
    }

    /**
     * For sender-side chunk/file transfer, use this API to acquire/release connections.
     */
    suspend fun withConnectionForTransfer(action: suspend (MeshConnectionPool.Connection) -> Unit) {
        val connection = try {
            connectionPool.acquireConnection(timeoutMs = 5000)
        } catch (e: Exception) {
            null
        }
        if (connection != null) {
            try {
                action(connection)
            } finally {
                connectionPool.releaseConnection(connection)
            }
        } else {
            // Optionally log: "No available connection for sender-side transfer"
        }
    }

    fun availableConnections(): Int = connectionPool.availableConnections()
    fun totalConnectionCount(): Int = connectionPool.totalConnectionCount()
    fun maxPoolSize(): Int = connectionPool.maxPoolSize()

    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scope.cancel()
        }
    }
}