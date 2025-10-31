package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import org.torproject.android.service.compute.IntelligentDistributedComputeService

/**
 * Listens for mesh ecosystem events: chunk transfer, broadcast responses, permission updates, etc.
 * Uses MeshConnectionPool for efficient communication for all chunk/file transfers.
 * Routes all mesh ecosystem events to registered handlers.
 */
class MeshEcosystemListener(
    private val meshNetworkInterface: MeshNetworkInterface,
    private val meshGossipService: MeshGossipService,
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

    // TODO: Add similar enable/disable for compute participation if needed
    // TODO: Add compute related broadcast listeners
    private fun registerBroadcastListeners() {
        // StorageNodeResponse broadcast listener
        coreGossipBroadcastService.registerListener("StorageNodeResponse") { senderId, bytes, type, msg ->
            if (isStorageParticipationEnabled && type == "StorageNodeResponse" && msg is StorageNodeResponse) {
                routeStorageNodeResponse(senderId, msg)
            }
        }
        // ChunkRetrievalResponse broadcast listener
        coreGossipBroadcastService.registerListener("ChunkRetrievalResponse") { senderId, bytes, type, msg ->
            if (isStorageParticipationEnabled && type == "ChunkRetrievalResponse" && msg is ChunkRetrievalResponse) {
                routeChunkRetrievalResponse(senderId, msg)
            }
        }
        // ReplicaResponse broadcast listener
        coreGossipBroadcastService.registerListener("ReplicaResponse") { senderId, bytes, type, msg ->
            if (isStorageParticipationEnabled && type == "ReplicaResponse" && msg is ReplicaResponse) {
                routeReplicaResponse(senderId, msg)
            }
        }
        // PermissionUpdateConfirmation broadcast listener
        coreGossipBroadcastService.registerListener("FilePermissionUpdateConfirmation") { _, _, type, msg ->
            if (isStorageParticipationEnabled && type == "FilePermissionUpdateConfirmation" && msg is MeshGossipService.FilePermissionUpdateConfirmation) {
                routePermissionUpdateConfirmation(msg)
            }
        }
        // TaskDataAccessUpdate broadcast listener
        coreGossipBroadcastService.registerListener("TaskDataAccessUpdateMessage") { _, _, type, msg ->
            if (type == "TaskDataAccessUpdateMessage" && msg is MeshGossipService.TaskDataAccessUpdateMessage) {
                routeTaskDataAccessUpdate(msg)
            }
        }
        // ChunkTransfer broadcast listener
        coreGossipBroadcastService.registerListener("ChunkTransfer") { senderId, _, type, msg ->
            if (isStorageParticipationEnabled && type == "ChunkTransfer" && msg is MeshGossipService.ChunkTransferMessage) {
                onChunkTransfer(senderId, msg)
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

    private fun routePermissionUpdateConfirmation(confirmation: MeshGossipService.FilePermissionUpdateConfirmation) {
        if (isStorageNodeEnabled) {
            storageManager?.handlePermissionUpdateConfirmation(confirmation)
        }
    }

    private fun routeTaskDataAccessUpdate(updateMsg: MeshGossipService.TaskDataAccessUpdateMessage) {
        computeService?.handleTaskDataAccessUpdate(updateMsg)
    }

    /**
     * Handles inbound chunk/file transfer events.
     * Both sender and receiver must acquire/release connections from the pool.
     * Only processes if storage node responsibilities are enabled.
     */
    fun onChunkTransfer(senderId: Int, chunk: MeshGossipService.ChunkTransferMessage) {
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