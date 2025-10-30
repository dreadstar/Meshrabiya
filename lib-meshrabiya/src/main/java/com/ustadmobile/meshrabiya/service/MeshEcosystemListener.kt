package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for mesh ecosystem events: chunk transfer, broadcast responses, permission updates, etc.
 * Uses MeshConnectionPool for efficient communication for all chunk/file transfers.
 * Routes all mesh ecosystem events to registered handlers.
 */
class MeshEcosystemListener(
    private val meshNetworkInterface: MeshNetworkInterface,
    private val meshGossipService: MeshGossipService,
    connectionPoolSize: Int = com.ustadmobile.meshrabiya.MeshrabiyaConstants.getConnectionPoolSize()
) {

    private val connectionPool = MeshConnectionPool(meshNetworkInterface, poolSize = connectionPoolSize)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Registered handlers
    private var storageManager: com.ustadmobile.meshrabiya.storage.DistributedStorageManager? = null
    private var computeService: org.torproject.android.service.compute.IntelligentDistributedComputeService? = null

    private val isShutdown = AtomicBoolean(false)
    private var isStorageParticipationEnabled: Boolean = false


    // Controls whether storage node responsibilities are enabled
    var isStorageNodeEnabled: Boolean = true
        set(value) {
            field = value
        }

    private val isShutdown = AtomicBoolean(false)

    init {
        registerBroadcastListeners()
    }

    fun registerStorageManager(manager: com.ustadmobile.meshrabiya.storage.DistributedStorageManager) {
        storageManager = manager
    }

    fun registerComputeService(service: org.torproject.android.service.compute.IntelligentDistributedComputeService) {
        computeService = service
    }

    fun setStorageParticipationEnabled(enabled: Boolean) {
        isStorageParticipationEnabled = enabled
    }

    private fun registerBroadcastListeners() {
        meshGossipService.registerStorageNodeResponseListener { senderId, response ->
            if (isStorageParticipationEnabled) {
                routeStorageNodeResponse(senderId, response)
            }
        }
        meshGossipService.registerChunkRetrievalResponseListener { senderId, response ->
            if (isStorageParticipationEnabled) {
                routeChunkRetrievalResponse(senderId, response)
            }
        }
        meshGossipService.registerReplicaResponseListener { senderId, response ->
            if (isStorageParticipationEnabled) {
                routeReplicaResponse(senderId, response)
            }
        }
        meshGossipService.registerPermissionUpdateConfirmationListener { confirmation ->
            if (isStorageParticipationEnabled) {
                routePermissionUpdateConfirmation(confirmation)
            }
        }
        meshGossipService.registerTaskDataAccessUpdateListener { updateMsg ->
            routeTaskDataAccessUpdate(updateMsg)
        }
        meshGossipService.registerChunkTransferListener { senderId, chunkMsg ->
            if (isStorageParticipationEnabled) {
                onChunkTransfer(senderId, chunkMsg)
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

    private fun routePermissionUpdateConfirmation(confirmation: MeshGossipService.PermissionUpdateConfirmation) {
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