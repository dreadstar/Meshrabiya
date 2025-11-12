package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
import com.ustadmobile.meshrabiya.storage.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse

/**
 * MeshEcosystemListener: Central router for mesh ecosystem broadcast messages.
 * 
 * Receives broadcasts from MeshGossipService, discriminates message types,
 * and routes to appropriate domain managers (storage, compute, etc.).
 * 
 * Key Responsibilities:
 * - Single point of registration with MeshGossipService for storage/compute events
 * - Message type discrimination and routing
 * - Connection pool management for transfers
 * - Participation state checking
 * 
 * Dependencies:
 * - MeshGossipService (injected, for receiving messages)
 * - MeshNetworkInterface (for connection pool)
 * - DistributedStorageManager (registered handler)
 * - IntelligentDistributedComputeService (registered handler)
 */
class MeshEcosystemListener(
    private val meshNetworkInterface: MeshNetworkInterface,
    private val meshGossipService: MeshGossipService,
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
        registerWithMeshGossipService()
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

    /**
     * Register generic listeners with MeshGossipService for each message type.
     * Each listener receives (senderId, message) and routes based on message content.
     */
    private fun registerWithMeshGossipService() {
        // Register listener for StorageNodeResponse messages
        meshGossipService.registerListener("StorageNodeResponse") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.StorageNodeResponseMessage ?: return@registerListener
                if (isStorageParticipationEnabled) {
                    // Extract requestId from response for correlation
                    val requestId = message.requestId ?: message.response.requestId ?: return@registerListener
                    routeStorageNodeResponse(requestId, senderId, message.response)
                }
            } catch (e: Exception) {
                // Log deserialization/routing error if needed
            }
        }

        // Register listener for ChunkRetrievalResponse messages
        meshGossipService.registerListener("ChunkRetrievalResponse") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.ChunkRetrievalResponseMessage ?: return@registerListener
                if (isStorageParticipationEnabled) {
                    val requestId = message.requestId ?: message.response.requestId ?: return@registerListener
                    routeChunkRetrievalResponse(requestId, senderId, message.response)
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for ReplicaResponse messages
        meshGossipService.registerListener("ReplicaResponse") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.ReplicaResponseMessage ?: return@registerListener
                if (isStorageParticipationEnabled) {
                    val requestId = message.requestId ?: message.response.requestId ?: return@registerListener
                    routeReplicaResponse(requestId, senderId, message.response)
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for ComputeNodeResponse messages
        meshGossipService.registerListener("ComputeNodeResponse") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.ComputeNodeResponseMessage ?: return@registerListener
                val requestId = message.requestId ?: return@registerListener
                routeComputeNodeResponse(requestId, senderId, message.response)
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for ComputeTaskRequest messages (incoming requests to this compute node)
        meshGossipService.registerListener("ComputeTaskRequest") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.ComputeTaskRequestMessage ?: return@registerListener
                // Generate a requestId if not present (for correlation)
                val requestId = message.metadata["requestId"] as? String ?: message.taskId
                routeIncomingComputeTaskRequest(requestId, senderId, message)
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for FilePermissionUpdateConfirmation messages
        meshGossipService.registerListener("FilePermissionUpdateConfirmation") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage ?: return@registerListener
                if (isStorageParticipationEnabled) {
                    routePermissionUpdateConfirmation(message)
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for TaskDataAccessUpdate messages
        meshGossipService.registerListener("TaskDataAccessUpdate") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.TaskDataAccessUpdateMessage ?: return@registerListener
                routeTaskDataAccessUpdate(message)
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for ChunkTransfer messages
        meshGossipService.registerListener("ChunkTransfer") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.ChunkTransferMessage ?: return@registerListener
                if (isStorageParticipationEnabled) {
                    onChunkTransfer(senderId, message)
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Register listener for EcosystemBroadcast messages
        meshGossipService.registerListener("EcosystemBroadcast") { senderId, rawMessage ->
            try {
                val message = rawMessage as? MeshEcosystemMessage.EcosystemBroadcastMessage ?: return@registerListener
                routeEcosystemBroadcast(senderId, message)
            } catch (e: Exception) {
                // Log error if needed
            }
        }
    }

    // === Centralized routing methods with requestId correlation ===

    /**
     * Route StorageNodeResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeStorageNodeResponse(requestId: String, senderId: Int, response: StorageNodeResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleStorageNodeResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ChunkRetrievalResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeChunkRetrievalResponse(requestId: String, senderId: Int, response: ChunkRetrievalResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleChunkRetrievalResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ReplicaResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeReplicaResponse(requestId: String, senderId: Int, response: ReplicaResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleReplicaResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ComputeNodeResponse to IntelligentDistributedComputeService.
     * Passes requestId for correlation with pending compute tasks.
     */
    private fun routeComputeNodeResponse(requestId: String, senderId: Int, response: ComputeNodeResponse) {
        computeService?.handleComputeNodeResponse(requestId, senderId, response)
    }

    /**
     * Route incoming ComputeTaskRequest to IntelligentDistributedComputeService.
     * This is called when another node broadcasts a compute task request to the mesh.
     * The local node evaluates whether it can handle the task and sends a response.
     */
    private fun routeIncomingComputeTaskRequest(
        requestId: String, 
        requesterNodeAddress: Int, 
        request: MeshEcosystemMessage.ComputeTaskRequestMessage
    ) {
        computeService?.handleIncomingComputeTaskRequest(requestId, requesterNodeAddress, request)
    }

    /**
     * Route permission update confirmation to DistributedStorageManager.
     */
    private fun routePermissionUpdateConfirmation(confirmation: MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage) {
        if (isStorageNodeEnabled) {
            storageManager?.handlePermissionUpdateConfirmation(confirmation)
        }
    }

    /**
     * Route task data access update to IntelligentDistributedComputeService.
     */
    private fun routeTaskDataAccessUpdate(updateMsg: MeshEcosystemMessage.TaskDataAccessUpdateMessage) {
        computeService?.handleTaskDataAccessUpdate(updateMsg)
    }

    /**
     * Handles inbound chunk/file transfer events.
     * Acquires connection from pool, processes transfer, releases connection.
     * Only processes if storage participation is enabled.
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
     * Route generic ecosystem broadcast messages.
     * Can be extended to handle specific broadcast types based on messageType or payload.
     */
    private fun routeEcosystemBroadcast(senderId: Int, broadcastMsg: MeshEcosystemMessage.EcosystemBroadcastMessage) {
        // Route based on broadcastMsg.messageType or payload as needed
        // For now, could pass to both managers if they implement generic handlers
        // storageManager?.handleEcosystemBroadcast(senderId, broadcastMsg)
        // computeService?.handleEcosystemBroadcast(senderId, broadcastMsg)
    }

    // === Connection Pool API for Sender-Side Transfers ===

    /**
     * Acquire a connection for sender-side chunk/file transfer.
     * Automatically releases connection after action completes.
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

    // === Connection Pool Diagnostic Methods ===

    fun availableConnections(): Int = connectionPool.availableConnections()
    fun totalConnectionCount(): Int = connectionPool.totalConnectionCount()
    fun maxPoolSize(): Int = connectionPool.maxPoolSize()

    // === Shutdown ===

    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scope.cancel()
        }
    }
}