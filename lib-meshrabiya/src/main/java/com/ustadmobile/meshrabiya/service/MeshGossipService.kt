package com.ustadmobile.meshrabiya.service

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.*
import com.ustadmobile.meshrabiya.storage.MeshChunk
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ScheduledExecutorService
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.mmcp.MmcpComputeTaskRequest
import com.ustadmobile.meshrabiya.storage.StorageNodeRequest
import com.ustadmobile.meshrabiya.storage.StorageNodeResponse
import com.ustadmobile.meshrabiya.storage.StorageCapabilities
import com.ustadmobile.meshrabiya.storage.AccessPattern

/**
 * MeshGossipService: Handles mesh-wide gossip messaging for storage, compute, and ecosystem events.
 * Uses MeshEcosystemMessage for all mesh-wide ecosystem events.
 */
class MeshGossipService private constructor(
    val virtualNode: VirtualNode,
    val meshRoleManager: MeshRoleManager,
    val context: Context,
    val scheduledExecutorService: ScheduledExecutorService,
    val originatingMessageManager: OriginatingMessageManager,
    val coreGossipBroadcastService: CoreGossipBroadcastService
) {

    private val emergentRoleManager = EmergentRoleManager.getInstance(context, virtualNode, meshRoleManager)
    private val androidVirtualNode = AndroidVirtualNode.getInstance(context, scheduledExecutorService)
    private val meshNetworkInterface: MeshNetworkInterface = MeshNetworkInterface.getInstance(context)

    companion object {
        @Volatile
        private var instance: MeshGossipService? = null

        fun getInstance(
            virtualNode: VirtualNode,
            meshRoleManager: MeshRoleManager,
            context: Context,
            scheduledExecutorService: ScheduledExecutorService,
            originatingMessageManager: OriginatingMessageManager,
            coreGossipBroadcastService: CoreGossipBroadcastService
        ): MeshGossipService {
            return instance ?: synchronized(this) {
                instance ?: MeshGossipService(
                    virtualNode,
                    meshRoleManager,
                    context,
                    scheduledExecutorService,
                    originatingMessageManager,
                    coreGossipBroadcastService
                ).also { instance = it }
            }
        }
    }

    // === Gossip-based Storage Node Discovery ===
    suspend fun broadcastStorageNodeRequestSync(request: StorageNodeRequest, timeoutMs: Long): List<StorageNodeResponse> {
        val responses = mutableListOf<StorageNodeResponse>()
        val listener: (Int, MeshEcosystemMessage) -> Unit = { senderId, msg ->
            if (msg is MeshEcosystemMessage.StorageNodeResponseMessage) {
                responses.add(msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("StorageNodeResponse", listener)
        coreGossipBroadcastService.sendBroadcast(MeshEcosystemMessage.StorageNodeRequestMessage(request))
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("StorageNodeResponse", listener)
        return responses
    }

    // === Gossip-based Chunk Location Discovery ===
    suspend fun broadcastChunkRetrievalRequestSync(fileId: String, timeoutMs: Long): List<ChunkRetrievalResponse> {
        val responses = mutableListOf<ChunkRetrievalResponse>()
        val listener: (Int, MeshEcosystemMessage) -> Unit = { senderId, msg ->
            if (msg is MeshEcosystemMessage.ChunkRetrievalResponseMessage) {
                responses.add(msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("ChunkRetrievalResponse", listener)
        coreGossipBroadcastService.sendBroadcast(
            MeshEcosystemMessage.ChunkRetrievalQueryMessage(
                com.ustadmobile.meshrabiya.vnet.ChunkRetrievalQuery(fileId)
            )
        )
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ChunkRetrievalResponse", listener)
        return responses
    }

    // === Gossip-based Replica Query ===
    suspend fun queryFileReplicasSync(fileId: String, timeoutMs: Long): List<String> {
        val replicaNodes = mutableListOf<String>()
        val listener: (Int, MeshEcosystemMessage) -> Unit = { senderId, msg ->
            if (msg is MeshEcosystemMessage.ReplicaResponseMessage) {
                replicaNodes.add(msg.response.nodeId)
            }
        }
        coreGossipBroadcastService.registerListener("ReplicaResponse", listener)
        coreGossipBroadcastService.sendBroadcast(
            MeshEcosystemMessage.ReplicaQueryMessage(
                com.ustadmobile.meshrabiya.vnet.ReplicaQuery(fileId)
            )
        )
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ReplicaResponse", listener)
        return replicaNodes
    }

    // === Gossip-based Storage Advertisement ===
    suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities) {
        coreGossipBroadcastService.sendBroadcast(
            MeshEcosystemMessage.StorageCapabilitiesMessage(capabilities)
        )
    }

    // === Gossip-based Compute Task Request ===
    suspend fun broadcastComputeTaskRequestSync(
        request: MmcpComputeTaskRequest,
        timeoutMs: Long
    ): List<ComputeNodeResponse> {
        val responses = mutableListOf<ComputeNodeResponse>()
        val listener: (Int, MeshEcosystemMessage) -> Unit = { senderId, msg ->
            if (msg is ComputeNodeResponseMessage) {
                responses.add(msg.response)
            }
        }
        coreGossipBroadcastService.registerListener("ComputeNodeResponse", listener)
        // For MMCP, use its own serialization if not a MeshEcosystemMessage
        coreGossipBroadcastService.sendBroadcast(request.toMeshEcosystemMessage())
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ComputeNodeResponse", listener)
        return responses
    }

    // === Gossip-based Chunk Transfer ===
    suspend fun sendChunkViaGossip(
        destinationNodeId: String,
        chunk: MeshChunk,
        chunkBytes: ByteArray
    ): Boolean {
        val hash = sha256(chunkBytes)
        val chunkMsg = MeshEcosystemMessage.ChunkTransferMessage(
            chunkId = chunk.chunkId,
            fileId = chunk.fileId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            fileName = chunk.fileName,
            relativePath = chunk.relativePath,
            chunkBytes = chunkBytes,
            hash = hash
        )
        androidVirtualNode.sendToNode(destinationNodeId.toInt(), chunkMsg.toBytes())
        return true
    }

    // === Permission Update Message Types ===
    enum class AccessType { ADDED, REMOVED }

    // === Gossip-based Permission Update Broadcast ===
    suspend fun broadcastFilePermissionUpdate(msg: MeshEcosystemMessage.FilePermissionUpdateMessage) {
        coreGossipBroadcastService.sendBroadcast(msg)
    }

    // === Gossip-based Permission Update Confirmation ===
    fun sendPermissionUpdateConfirmation(
        destinationNodeId: String,
        confirmation: MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage
    ) {
        androidVirtualNode.sendToNode(destinationNodeId.toInt(), confirmation.toBytes())
    }

    // === Gossip-based Task Data Access Update Broadcast ===
    suspend fun broadcastTaskDataAccessUpdate(msg: MeshEcosystemMessage.TaskDataAccessUpdateMessage) {
        coreGossipBroadcastService.sendBroadcast(msg)
    }

    // === Gossip-based Ecosystem Broadcast ===
    suspend fun broadcastEcosystemBroadcast(
        broadcastId: String,
        senderId: String,
        messageType: String,
        payload: ByteArray
    ) {
        val msg = MeshEcosystemMessage.EcosystemBroadcastMessage(
            broadcastId = broadcastId,
            senderId = senderId,
            messageType = messageType,
            payload = payload
        )
        coreGossipBroadcastService.sendBroadcast(msg)
    }

    // === Utility: SHA-256 Hash ===
    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

// --- ComputeNodeResponseMessage stub ---
// If you have a MeshEcosystemMessage subclass for ComputeNodeResponse, use it.
// Otherwise, define it here or import from the correct location.
data class ComputeNodeResponse(val nodeId: String, val result: String)
class ComputeNodeResponseMessage(val response: ComputeNodeResponse) : MeshEcosystemMessage("ComputeNodeResponse") {
    override fun toBytes(): ByteArray {
        // Implement serialization as needed
        return ByteArray(0)
    }
}