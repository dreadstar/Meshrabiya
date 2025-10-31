package com.ustadmobile.meshrabiya.service

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.*
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.storage.MeshChunk
import kotlinx.coroutines.*
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.core.MessageBufferPacker
import java.security.MessageDigest
import java.util.concurrent.ScheduledExecutorService
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.mmcp.MmcpComputeTaskRequest

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
            originatingMessageManager: OriginatingMessageManager
        ): MeshGossipService {
            val androidVirtualNode = AndroidVirtualNode.getInstance(context, scheduledExecutorService)
            val coreGossipBroadcastService = CoreGossipBroadcastService(
                originatingMessageManager = originatingMessageManager,
                sendToNode = { addr, bytes -> androidVirtualNode.sendToNode(addr, bytes) }
            )
            return instance ?: synchronized(this) {
                instance ?: MeshGossipService(
                    virtualNode, meshRoleManager, context, scheduledExecutorService, originatingMessageManager, coreGossipBroadcastService
                ).also { instance = it }
            }
        }
    }

    // === Gossip-based Storage Node Discovery ===
    suspend fun broadcastStorageNodeRequestSync(request: StorageNodeRequest, timeoutMs: Long): List<StorageNodeResponse> {
        val requestBytes = serializeMessage(request, "StorageNodeRequest")
        val responses = mutableListOf<StorageNodeResponse>()
        val listener: (Int, ByteArray, String, Any?) -> Unit = { senderId, bytes, type, msg ->
            if (type == "StorageNodeResponse" && msg is StorageNodeResponse) {
                responses.add(msg)
            }
        }
        coreGossipBroadcastService.registerListener("StorageNodeResponse", listener)
        coreGossipBroadcastService.sendBroadcast(requestBytes, "StorageNodeRequest")
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("StorageNodeResponse", listener)
        return responses
    }

    // === Gossip-based Chunk Location Discovery ===
    suspend fun broadcastChunkRetrievalRequestSync(fileId: String, timeoutMs: Long): List<ChunkRetrievalResponse> {
        val queryMsg = ChunkRetrievalQuery(fileId)
        val queryBytes = serializeMessage(queryMsg, "ChunkRetrievalQuery")
        val responses = mutableListOf<ChunkRetrievalResponse>()
        val listener: (Int, ByteArray, String, Any?) -> Unit = { senderId, bytes, type, msg ->
            if (type == "ChunkRetrievalResponse" && msg is ChunkRetrievalResponse) {
                responses.add(msg)
            }
        }
        coreGossipBroadcastService.registerListener("ChunkRetrievalResponse", listener)
        coreGossipBroadcastService.sendBroadcast(queryBytes, "ChunkRetrievalQuery")
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ChunkRetrievalResponse", listener)
        return responses
    }

    // === Gossip-based Replica Query ===
    suspend fun queryFileReplicasSync(fileId: String, timeoutMs: Long): List<String> {
        val queryBytes = serializeMessage(ReplicaQuery(fileId), "ReplicaQuery")
        val replicaNodes = mutableListOf<String>()
        val listener: (Int, ByteArray, String, Any?) -> Unit = { senderId, bytes, type, msg ->
            if (type == "ReplicaResponse" && msg is ReplicaResponse) {
                replicaNodes.add(msg.nodeId)
            }
        }
        coreGossipBroadcastService.registerListener("ReplicaResponse", listener)
        coreGossipBroadcastService.sendBroadcast(queryBytes, "ReplicaQuery")
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ReplicaResponse", listener)
        return replicaNodes
    }

    // === Gossip-based Storage Advertisement ===
    suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities) {
        val msgBytes = serializeMessage(capabilities, "StorageCapabilities")
        coreGossipBroadcastService.sendBroadcast(msgBytes, "StorageCapabilities")
    }

    suspend fun broadcastComputeTaskRequestSync(
        request: MmcpComputeTaskRequest,
        timeoutMs: Long
    ): List<ComputeNodeResponse> {
        val requestBytes = request.toBytes()
        val responses = mutableListOf<ComputeNodeResponse>()
        val listener: (Int, ByteArray, String, Any?) -> Unit = { senderId, bytes, type, msg ->
            if (type == "ComputeNodeResponse" && msg is ComputeNodeResponse) {
                responses.add(msg)
            }
        }
        coreGossipBroadcastService.registerListener("ComputeNodeResponse", listener)
        coreGossipBroadcastService.sendBroadcast(requestBytes, "MmcpComputeTaskRequest")
        delay(timeoutMs)
        coreGossipBroadcastService.unregisterListener("ComputeNodeResponse", listener)
        return responses
    }

    fun addTaskRequest(localRequest: LocalComputeTaskRequest) {
        taskRequestList.add(localRequest)
        CoroutineScope(Dispatchers.IO).launch {
            val responses = meshGossipService.broadcastComputeTaskRequestSync(
                localRequest.mmcpRequest,
                MeshrabiyaConstants.getTimeoutMs()
            )
            handleComputeNodeResponses(localRequest, responses)
        }
    }

    // === Gossip-based Chunk Transfer ===
    suspend fun sendChunkViaGossip(
        destinationNodeId: String,
        chunk: MeshChunk,
        chunkBytes: ByteArray
    ): Boolean {
        val hash = sha256(chunkBytes)
        val chunkMsg = ChunkTransferMessage(
            chunkId = chunk.chunkId,
            fileId = chunk.fileId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            fileName = chunk.fileName,
            relativePath = chunk.relativePath,
            chunkBytes = chunkBytes,
            hash = hash
        )
        val msgBytes = serializeMessage(chunkMsg, "ChunkTransfer")
        androidVirtualNode.sendToNode(destinationNodeId.toInt(), msgBytes)
        return true
    }

    // === Permission Update Message Types ===
    enum class AccessType { ADDED, REMOVED }

    data class FilePermissionUpdateMessage(
        val fileId: String,
        val newRecipientKeyIds: List<Long>,
        val senderNodeId: String
    )

    data class FilePermissionUpdateConfirmation(
        val fileId: String,
        val chunkId: String,
        val storageNodeId: String,
        val status: Boolean
    )

    data class TaskDataAccessUpdateMessage(
        val fileId: String,
        val affectedTaskUUIDs: List<String>,
        val accessType: AccessType
    )

    // === Gossip-based Permission Update Broadcast ===
    suspend fun broadcastFilePermissionUpdate(msg: FilePermissionUpdateMessage) {
        val msgBytes = serializeMessage(msg, "FilePermissionUpdateMessage")
        coreGossipBroadcastService.sendBroadcast(msgBytes, "FilePermissionUpdateMessage")
    }

    // === Gossip-based Permission Update Confirmation ===
    fun sendPermissionUpdateConfirmation(
        destinationNodeId: String,
        confirmation: FilePermissionUpdateConfirmation
    ) {
        val msgBytes = serializeMessage(confirmation, "FilePermissionUpdateConfirmation")
        androidVirtualNode.sendToNode(destinationNodeId.toInt(), msgBytes)
    }

    // === Gossip-based Task Data Access Update Broadcast ===
    suspend fun broadcastTaskDataAccessUpdate(msg: TaskDataAccessUpdateMessage) {
        val msgBytes = serializeMessage(msg, "TaskDataAccessUpdateMessage")
        coreGossipBroadcastService.sendBroadcast(msgBytes, "TaskDataAccessUpdateMessage")
    }

    // === Gossip Message Models ===
    data class ChunkTransferMessage(
        val chunkId: String,
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val fileName: String,
        val relativePath: String,
        val chunkBytes: ByteArray,
        val hash: String
    )

    data class ChunkRetrievalQuery(val fileId: String)
    data class ChunkRetrievalResponse(
        val chunkId: String,
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val nodeId: String,
        val fileName: String,
        val relativePath: String,
        val chunkSize: Long
    )
    data class ReplicaQuery(val fileId: String)
    data class ReplicaResponse(val nodeId: String)
    data class MeshMessage(
        val recipientNodeId: String,
        val messageType: String,
        val payload: Map<String, Any>
    )

    // === MessagePack Serialization ===
    private fun serializeMessage(obj: Any, type: String): ByteArray {
        val packer: MessageBufferPacker = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        when (obj) {
            is StorageNodeRequest -> {
                packer.packLong(obj.requiredSpace)
                packer.packString(obj.fileName)
                packer.packString(obj.fileId ?: "")
                packer.packString(obj.senderId)
            }
            is StorageNodeResponse -> {
                packer.packString(obj.nodeId)
                packer.packLong(obj.availableSpace)
                packer.packString(obj.systemState)
                packer.packString(obj.url)
                packer.packInt(obj.latency)
                packer.packFloat(obj.fitnessScore)
            }
            is ChunkTransferMessage -> {
                packer.packString(obj.chunkId)
                packer.packString(obj.fileId)
                packer.packInt(obj.chunkIndex)
                packer.packInt(obj.totalChunks)
                packer.packString(obj.fileName)
                packer.packString(obj.relativePath)
                packer.packBinaryHeader(obj.chunkBytes.size)
                packer.writePayload(obj.chunkBytes)
                packer.packString(obj.hash)
            }
            is ChunkRetrievalQuery -> {
                packer.packString(obj.fileId)
            }
            is ChunkRetrievalResponse -> {
                packer.packString(obj.chunkId)
                packer.packString(obj.fileId)
                packer.packInt(obj.chunkIndex)
                packer.packInt(obj.totalChunks)
                packer.packString(obj.nodeId)
                packer.packString(obj.fileName)
                packer.packString(obj.relativePath)
                packer.packLong(obj.chunkSize)
            }
            is ReplicaQuery -> {
                packer.packString(obj.fileId)
            }
            is ReplicaResponse -> {
                packer.packString(obj.nodeId)
            }
            is StorageCapabilities -> {
                packer.packLong(obj.totalOffered)
                packer.packLong(obj.currentlyUsed)
                packer.packInt(obj.replicationFactor)
                packer.packBoolean(obj.compressionSupported)
                packer.packBoolean(obj.encryptionSupported)
                packer.packArrayHeader(obj.accessPatterns.size)
                obj.accessPatterns.forEach { packer.packString(it.name) }
            }
            is MeshMessage -> {
                packer.packString(obj.recipientNodeId)
                packer.packString(obj.messageType)
                packer.packMapHeader(obj.payload.size)
                obj.payload.forEach { (key, value) ->
                    packer.packString(key)
                    when (value) {
                        is String -> packer.packString(value)
                        is Int -> packer.packInt(value)
                        is Boolean -> packer.packBoolean(value)
                        is Float -> packer.packFloat(value)
                        is Double -> packer.packDouble(value)
                        else -> packer.packString(value.toString())
                    }
                }
            }
            is FilePermissionUpdateMessage -> {
                packer.packString(obj.fileId)
                packer.packArrayHeader(obj.newRecipientKeyIds.size)
                obj.newRecipientKeyIds.forEach { packer.packLong(it) }
                packer.packString(obj.senderNodeId)
            }
            is FilePermissionUpdateConfirmation -> {
                packer.packString(obj.fileId)
                packer.packString(obj.chunkId)
                packer.packString(obj.storageNodeId)
                packer.packBoolean(obj.status)
            }
            is TaskDataAccessUpdateMessage -> {
                packer.packString(obj.fileId)
                packer.packArrayHeader(obj.affectedTaskUUIDs.size)
                obj.affectedTaskUUIDs.forEach { packer.packString(it) }
                packer.packString(obj.accessType.name)
            }
        }
        packer.close()
        return packer.toByteArray()
    }

    private fun deserializeMessage(bytes: ByteArray): Pair<String, Any?> {
        val unpacker: MessageUnpacker = MessagePack.newDefaultUnpacker(bytes)
        val type: String = unpacker.unpackString()
        val message: Any? = when (type) {
            "StorageNodeRequest" -> StorageNodeRequest(
                requiredSpace = unpacker.unpackLong(),
                fileName = unpacker.unpackString(),
                fileId = unpacker.unpackString().ifEmpty { null },
                senderId = unpacker.unpackString()
            )
            "StorageNodeResponse" -> StorageNodeResponse(
                nodeId = unpacker.unpackString(),
                availableSpace = unpacker.unpackLong(),
                systemState = unpacker.unpackString(),
                url = unpacker.unpackString(),
                latency = unpacker.unpackInt(),
                fitnessScore = unpacker.unpackFloat()
            )
            "ChunkTransfer" -> {
                val chunkId = unpacker.unpackString()
                val fileId = unpacker.unpackString()
                val chunkIndex = unpacker.unpackInt()
                val totalChunks = unpacker.unpackInt()
                val fileName = unpacker.unpackString()
                val relativePath = unpacker.unpackString()
                val chunkSize = unpacker.unpackBinaryHeader()
                val chunkBytes = ByteArray(chunkSize)
                unpacker.readPayload(chunkBytes)
                val hash = unpacker.unpackString()
                ChunkTransferMessage(chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash)
            }
            "ChunkRetrievalQuery" -> ChunkRetrievalQuery(unpacker.unpackString())
            "ChunkRetrievalResponse" -> ChunkRetrievalResponse(
                chunkId = unpacker.unpackString(),
                fileId = unpacker.unpackString(),
                chunkIndex = unpacker.unpackInt(),
                totalChunks = unpacker.unpackInt(),
                nodeId = unpacker.unpackString(),
                fileName = unpacker.unpackString(),
                relativePath = unpacker.unpackString(),
                chunkSize = unpacker.unpackLong()
            )
            "ReplicaQuery" -> ReplicaQuery(unpacker.unpackString())
            "ReplicaResponse" -> ReplicaResponse(unpacker.unpackString())
            "StorageCapabilities" -> StorageCapabilities(
                totalOffered = unpacker.unpackLong(),
                currentlyUsed = unpacker.unpackLong(),
                replicationFactor = unpacker.unpackInt(),
                compressionSupported = unpacker.unpackBoolean(),
                encryptionSupported = unpacker.unpackBoolean(),
                accessPatterns = List(unpacker.unpackArrayHeader()) { AccessPattern.valueOf(unpacker.unpackString()) }.toSet()
            )
            "MeshMessage" -> {
                val recipientNodeId = unpacker.unpackString()
                val messageType = unpacker.unpackString()
                val payloadSize = unpacker.unpackMapHeader()
                val payload = mutableMapOf<String, Any>()
                repeat(payloadSize) {
                    val key = unpacker.unpackString()
                    val value = unpacker.unpackString()
                    payload[key] = value
                }
                MeshMessage(recipientNodeId, messageType, payload)
            }
            "FilePermissionUpdateMessage" -> {
                val fileId = unpacker.unpackString()
                val keyCount = unpacker.unpackArrayHeader()
                val newRecipientKeyIds = mutableListOf<Long>()
                repeat(keyCount) { newRecipientKeyIds.add(unpacker.unpackLong()) }
                val senderNodeId = unpacker.unpackString()
                FilePermissionUpdateMessage(fileId, newRecipientKeyIds, senderNodeId)
            }
            "FilePermissionUpdateConfirmation" -> {
                val fileId = unpacker.unpackString()
                val chunkId = unpacker.unpackString()
                val storageNodeId = unpacker.unpackString()
                val status = unpacker.unpackBoolean()
                FilePermissionUpdateConfirmation(fileId, chunkId, storageNodeId, status)
            }
            "TaskDataAccessUpdateMessage" -> {
                val fileId = unpacker.unpackString()
                val uuidCount = unpacker.unpackArrayHeader()
                val affectedTaskUUIDs = mutableListOf<String>()
                repeat(uuidCount) { affectedTaskUUIDs.add(unpacker.unpackString()) }
                val accessType = AccessType.valueOf(unpacker.unpackString())
                TaskDataAccessUpdateMessage(fileId, affectedTaskUUIDs, accessType)
            }
            else -> null
        }
        unpacker.close()
        return Pair(type, message)
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}