package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.service.AccessType
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind

import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.core.MessageBufferPacker



// --- AnySerializer for kotlinx.serialization ---
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Any", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Any) {
        encoder.encodeString(value.toString())
    }
    override fun deserialize(decoder: Decoder): Any = decoder.decodeString()
}
// ===== DATA STRUCTURES FOR ECOSYSTEM MESSAGES =====

/**
 * Storage node request for chunk storage in distributed network.
 * Updated for canonical workflow compliance.
 */
data class StorageNodeRequest(
    val requestId: String,
    val chunkId: String,
    val chunkIndex: Int,
    val fileId: String,
    val desiredReplicas: Int? = null,
    val chunkSizeBytes: Long,
    val replicaCount: Int = 0,
    // Legacy fields for backward compatibility
    val requiredSpace: Long = chunkSizeBytes,
    val fileName: String = "",
    val senderId: String = ""
)

/**
 * Storage node response with capacity and service key.
 * Updated for canonical workflow compliance.
 */
data class StorageNodeResponse(
    val nodeId: String,
    val availableSpace: Long,
    val totalStorageAllocated: Long = 0L,
    val systemState: String,
    val url: String,
    val latency: Int,
    val fitnessScore: Float,
    val fileId: String = "",
    val servicePublicKey: ByteArray = ByteArray(0)
)

/**
 * Query to retrieve chunk information from storage nodes.
 * Updated for canonical workflow retry support.
 */
data class ChunkRetrievalQuery(
    val fileId: String,
    val chunkIndexes: List<Int>? = null
)

/**
 * Response containing chunk retrieval metadata from storage nodes.
 */
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

/**
 * Query to check replica status in distributed storage.
 */
data class ReplicaQuery(
    val fileId: String
)

/**
 * Response containing replica status information.
 */
data class ReplicaResponse(
    val fileId: String
)





// ===== MESSAGE TYPE REGISTRATION & ENUMERATION =====

/**
 * Enum of all canonical MeshEcosystemMessage types for registration and handler logic.
 * Used for message dispatch, handler registration, and type safety.
 */
enum class MessageType(val type: String) {
    STORAGE_NODE_REQUEST("StorageNodeRequest"),
    STORAGE_NODE_RESPONSE("StorageNodeResponse"),
    CHUNK_TRANSFER("ChunkTransfer"),
    CHUNK_RETRIEVAL_QUERY("ChunkRetrievalQuery"),
    CHUNK_RETRIEVAL_RESPONSE("ChunkRetrievalResponse"),
    REPLICA_QUERY("ReplicaQuery"),
    REPLICA_RESPONSE("ReplicaResponse"),
    FILE_PERMISSION_UPDATE("FilePermissionUpdateMessage"),
    FILE_PERMISSION_UPDATE_CONFIRMATION("FilePermissionUpdateConfirmation"),
    ECOSYSTEM_BROADCAST("EcosystemBroadcast"),
    COMPUTE_TASK_REQUEST("ComputeTaskRequest"),
    COMPUTE_NODE_RESPONSE("ComputeNodeResponse"),
    TASK_COMPLETED("TaskCompleted"),
    TASK_SCHEDULED("TaskScheduled"),
    TASK_ASSIGNMENT("TaskAssignment");

    companion object {
        private val map = values().associateBy(MessageType::type)
        fun fromType(type: String): MessageType? = map[type]
    }
}

// ===== MESH ECOSYSTEM MESSAGE BASE CLASS =====

/**
 * Canonical Mesh Ecosystem Message class for all mesh-wide ecosystem events, including broadcast.
 * Provides serialization/deserialization and VirtualPacket conversion.
 */
sealed class MeshEcosystemMessage(
    val type: String
) {
    abstract fun toBytes(): ByteArray

    fun toVirtualPacket(
        toAddr: Int,
        fromAddr: Int,
        toPort: Int,
        fromPort: Int,
        lastHopAddr: Int = 0,
        hopCount: Byte = 0,
        payloadOffset: Int = VirtualPacketHeader.HEADER_SIZE
    ): VirtualPacket {
        val payload = toBytes()
        val packetData = ByteArray(payload.size + VirtualPacketHeader.HEADER_SIZE)
        System.arraycopy(payload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, payload.size)
        return VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                toAddr = toAddr,
                toPort = toPort,
                fromAddr = fromAddr,
                fromPort = fromPort,
                lastHopAddr = lastHopAddr,
                hopCount = hopCount,
                maxHops = 0,
                payloadSize = payload.size
            ),
            data = packetData,
            payloadOffset = payloadOffset
        )
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MeshEcosystemMessage {
            val unpacker: MessageUnpacker = MessagePack.newDefaultUnpacker(bytes)
            val type: String = unpacker.unpackString()
            val message: MeshEcosystemMessage = when (type) {
                "StorageNodeRequest" -> {
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()
                    val desiredReplicas = if (unpacker.unpackBoolean()) {
                        unpacker.unpackInt()
                    } else null
                    val chunkSizeBytes = unpacker.unpackLong()
                    val replicaCount = unpacker.unpackInt()
                    val requiredSpace = unpacker.unpackLong()
                    val fileName = unpacker.unpackString()
                    val senderId = unpacker.unpackString()
                    StorageNodeRequestMessage(
                        StorageNodeRequest(
                            requestId, chunkId, chunkIndex, fileId, desiredReplicas,
                            chunkSizeBytes, replicaCount, requiredSpace, fileName, senderId
                        )
                    )
                }
                "StorageNodeResponse" -> {
                    val requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
                    val nodeId = unpacker.unpackString()
                    val availableSpace = unpacker.unpackLong()
                    val totalStorageAllocated = unpacker.unpackLong()
                    val systemState = unpacker.unpackString()
                    val url = unpacker.unpackString()
                    val latency = unpacker.unpackInt()
                    val fitnessScore = unpacker.unpackFloat()
                    val fileId = unpacker.unpackString()
                    val serviceKeySize = unpacker.unpackBinaryHeader()
                    val servicePublicKey = ByteArray(serviceKeySize)
                    unpacker.readPayload(servicePublicKey)
                    StorageNodeResponseMessage(
                        response = StorageNodeResponse(
                            nodeId, availableSpace, totalStorageAllocated, systemState, 
                            url, latency, fitnessScore, fileId, servicePublicKey
                        ),
                        requestId = requestId
                    )
                }
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
                    val replicaCount = unpacker.unpackInt()
                    
                    // Unpack recipientKeyIds
                    val recipientKeyIds = List(unpacker.unpackArrayHeader()) {
                        unpacker.unpackLong()
                    }
                    
                    // Unpack sessionKeys
                    val sessionKeysSize = unpacker.unpackMapHeader()
                    val sessionKeys = mutableMapOf<Long, ByteArray>()
                    repeat(sessionKeysSize) {
                        val keyId = unpacker.unpackLong()
                        val keySize = unpacker.unpackBinaryHeader()
                        val key = ByteArray(keySize)
                        unpacker.readPayload(key)
                        sessionKeys[keyId] = key
                    }
                    
                    // Unpack desiredReplicas (nullable)
                    val desiredReplicas = if (unpacker.unpackBoolean()) {
                        unpacker.unpackInt()
                    } else null
                    
                    ChunkTransferMessage(
                        chunkId, 
                        fileId, 
                        chunkIndex, 
                        totalChunks, 
                        fileName, 
                        relativePath, 
                        chunkBytes, 
                        hash, 
                        replicaCount, recipientKeyIds, 
                        sessionKeys, 
                        desiredReplicas
                    ) as MeshEcosystemMessage
                }
                "ChunkRetrievalQuery" -> {
                    val fileId = unpacker.unpackString()
                    val chunkIndexes = if (unpacker.unpackBoolean()) {
                        List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    } else null
                    ChunkRetrievalQueryMessage(
                        ChunkRetrievalQuery(fileId, chunkIndexes)
                    )
                }
                "ChunkRetrievalResponse" -> ChunkRetrievalResponseMessage(
                    response = ChunkRetrievalResponse(
                        chunkId = unpacker.unpackString(),
                        fileId = unpacker.unpackString(),
                        chunkIndex = unpacker.unpackInt(),
                        totalChunks = unpacker.unpackInt(),
                        nodeId = unpacker.unpackString(),
                        fileName = unpacker.unpackString(),
                        relativePath = unpacker.unpackString(),
                        chunkSize = unpacker.unpackLong()
                    ),
                    requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
                )
                "ReplicaQuery" -> ReplicaQueryMessage(
                    ReplicaQuery(unpacker.unpackString())
                )
                "ReplicaResponse" -> ReplicaResponseMessage(
                    response = ReplicaResponse(unpacker.unpackString()),
                    requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
                )
                // DEPRECATED: Storage capabilities superseded by OriginatorMessage with MeshRole.STORAGE
                // "StorageCapabilities" -> StorageCapabilitiesMessage(
                //     StorageCapabilities(
                //         totalOffered = unpacker.unpackLong(),
                //         currentlyUsed = unpacker.unpackLong(),
                //         replicationFactor = unpacker.unpackInt(),
                //         compressionSupported = unpacker.unpackBoolean(),
                //         encryptionSupported = unpacker.unpackBoolean(),
                //         accessPatterns = List(unpacker.unpackArrayHeader()) { AccessPattern.valueOf(unpacker.unpackString()) }.toSet()
                //     )
                // )
                "FilePermissionUpdateMessage" -> {
                    val fileId = unpacker.unpackString()
                    val addedJson = unpacker.unpackString()
                    val removedJson = unpacker.unpackString()
                    val addedRecipients = Json.decodeFromString<List<RecipientEntry>>(addedJson)
                    val removedRecipients = Json.decodeFromString<List<RecipientEntry>>(removedJson)
                    val chunkIndexes = if (unpacker.unpackBoolean()) {
                        List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    } else null
                    val senderNodeId = unpacker.unpackString()
                    FilePermissionUpdateMessage(fileId, addedRecipients, removedRecipients, chunkIndexes, senderNodeId)
                }
                "FilePermissionUpdateConfirmation" -> {
                    val nodeId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndexes = List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    val status = unpacker.unpackBoolean()
                    FilePermissionUpdateConfirmationMessage(nodeId, fileId, chunkIndexes, status)
                }
                
                "EcosystemBroadcast" -> EcosystemBroadcastMessage(
                    broadcastId = unpacker.unpackString(),
                    senderId = unpacker.unpackString(),
                    messageType = unpacker.unpackString(),
                    payload = unpacker.readPayload(unpacker.unpackBinaryHeader())
                )
                "ComputeTaskRequest" -> ComputeTaskRequestMessage.fromUnpacker(unpacker)
                "ComputeNodeResponse" -> ComputeNodeResponseMessage.fromUnpacker(unpacker)
                "TaskCompleted" -> TaskCompletedMessage.fromUnpacker(unpacker)
                "TaskScheduled" -> TaskScheduledMessage.fromUnpacker(unpacker)
                "TaskAssignment" -> TaskAssignmentMessage.fromUnpacker(unpacker)
                else -> throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            }
            unpacker.close()
            return message
        }
    }
}

// === Message Subclasses ===

data class StorageNodeResponseMessage(
    val response: StorageNodeResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("StorageNodeResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packString(response.nodeId)
        packer.packLong(response.availableSpace)
        packer.packLong(response.totalStorageAllocated)
        packer.packString(response.systemState)
        packer.packString(response.url)
        packer.packInt(response.latency)
        packer.packFloat(response.fitnessScore)
        packer.packString(response.fileId)
        packer.packBinaryHeader(response.servicePublicKey.size)
        packer.writePayload(response.servicePublicKey)
        packer.close()
        return packer.toByteArray()
    }
}

data class StorageNodeRequestMessage(val request: StorageNodeRequest) : MeshEcosystemMessage("StorageNodeRequest") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(request.requestId)
        packer.packString(request.chunkId)
        packer.packInt(request.chunkIndex)
        packer.packString(request.fileId)
        if (request.desiredReplicas != null) {
            packer.packBoolean(true)
            packer.packInt(request.desiredReplicas)
        } else {
            packer.packBoolean(false)
        }
        packer.packLong(request.chunkSizeBytes)
        packer.packInt(request.replicaCount)
        packer.packLong(request.requiredSpace)
        packer.packString(request.fileName)
        packer.packString(request.senderId)
        packer.close()
        return packer.toByteArray()
    }
    companion object {
        /**
            * Factory: Deserialize bytes to MeshEcosystemMessage, dispatching by MessageType.
            * All message types must be registered in MessageType and handled here.
            */
        fun fromBytes(bytes: ByteArray): MeshEcosystemMessage {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val type = unpacker.unpackString()
            val messageType = MessageType.fromType(type)
                ?: throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            val message: MeshEcosystemMessage = when (messageType) {
                MessageType.STORAGE_NODE_REQUEST -> {
                    // ...existing code for StorageNodeRequest...
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()
                    val hasDesiredReplicas = unpacker.unpackBoolean()
                    val desiredReplicas = if (hasDesiredReplicas) unpacker.unpackInt() else null
                    val chunkSizeBytes = unpacker.unpackLong()
                    val replicaCount = unpacker.unpackInt()
                    val requiredSpace = unpacker.unpackLong()
                    val fileName = unpacker.unpackString()
                    val senderId = unpacker.unpackString()
                    StorageNodeRequestMessage(
                        StorageNodeRequest(
                            requestId, chunkId, chunkIndex, fileId, desiredReplicas, chunkSizeBytes, replicaCount, requiredSpace, fileName, senderId
                        )
                    )
                }
                MessageType.STORAGE_NODE_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val nodeId = unpacker.unpackString()
                    val availableSpace = unpacker.unpackLong()
                    val totalStorageAllocated = unpacker.unpackLong()
                    val systemState = unpacker.unpackString()
                    val url = unpacker.unpackString()
                    val latency = unpacker.unpackInt()
                    val fitnessScore = unpacker.unpackFloat()
                    val fileId = unpacker.unpackString()
                    val keyLen = unpacker.unpackBinaryHeader()
                    val servicePublicKey = ByteArray(keyLen)
                    unpacker.readPayload(servicePublicKey)
                    StorageNodeResponseMessage(
                        StorageNodeResponse(
                            nodeId, availableSpace, totalStorageAllocated, systemState, url, latency, fitnessScore, fileId, servicePublicKey
                        ),
                        requestId
                    )
                }
                MessageType.CHUNK_TRANSFER -> {
                    val chunkId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val totalChunks = unpacker.unpackInt()
                    val fileName = unpacker.unpackString()
                    val relativePath = unpacker.unpackString()
                    val chunkBytesLen = unpacker.unpackBinaryHeader()
                    val chunkBytes = ByteArray(chunkBytesLen)
                    unpacker.readPayload(chunkBytes)
                    val hash = unpacker.unpackString()
                    val replicaCount = unpacker.unpackInt()
                    val recipientKeyIdsLen = unpacker.unpackArrayHeader()
                    val recipientKeyIds = List(recipientKeyIdsLen) { unpacker.unpackLong() }
                    val sessionKeysLen = unpacker.unpackMapHeader()
                    val sessionKeys = mutableMapOf<Long, ByteArray>()
                    repeat(sessionKeysLen) {
                        val keyId = unpacker.unpackLong()
                        val encKeyLen = unpacker.unpackBinaryHeader()
                        val encKey = ByteArray(encKeyLen)
                        unpacker.readPayload(encKey)
                        sessionKeys[keyId] = encKey
                    }
                    val hasDesiredReplicas = unpacker.unpackBoolean()
                    val desiredReplicas = if (hasDesiredReplicas) unpacker.unpackInt() else null
                    ChunkTransferMessage(
                        chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash, replicaCount, recipientKeyIds, sessionKeys, desiredReplicas
                    )
                }
                MessageType.CHUNK_RETRIEVAL_QUERY -> {
                    val fileId = unpacker.unpackString()
                    val hasChunkIndexes = unpacker.unpackBoolean()
                    val chunkIndexes = if (hasChunkIndexes) {
                        val len = unpacker.unpackArrayHeader()
                        List(len) { unpacker.unpackInt() }
                    } else null
                    ChunkRetrievalQueryMessage(ChunkRetrievalQuery(fileId, chunkIndexes))
                }
                MessageType.CHUNK_RETRIEVAL_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val totalChunks = unpacker.unpackInt()
                    val nodeId = unpacker.unpackString()
                    val fileName = unpacker.unpackString()
                    val relativePath = unpacker.unpackString()
                    val chunkSize = unpacker.unpackLong()
                    ChunkRetrievalResponseMessage(
                        ChunkRetrievalResponse(chunkId, fileId, chunkIndex, totalChunks, nodeId, fileName, relativePath, chunkSize),
                        requestId
                    )
                }
                MessageType.REPLICA_QUERY -> {
                    val fileId = unpacker.unpackString()
                    ReplicaQueryMessage(ReplicaQuery(fileId))
                }
                MessageType.REPLICA_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    ReplicaResponseMessage(ReplicaResponse(fileId), requestId)
                }
                MessageType.FILE_PERMISSION_UPDATE -> {
                    val fileId = unpacker.unpackString()
                    val addedJson = unpacker.unpackString()
                    val removedJson = unpacker.unpackString()
                    val hasChunkIndexes = unpacker.unpackBoolean()
                    val chunkIndexes = if (hasChunkIndexes) {
                        val len = unpacker.unpackArrayHeader()
                        List(len) { unpacker.unpackInt() }
                    } else null
                    val senderNodeId = unpacker.unpackString()
                    val addedRecipients = Json.decodeFromString<List<RecipientEntry>>(addedJson)
                    val removedRecipients = Json.decodeFromString<List<RecipientEntry>>(removedJson)
                    FilePermissionUpdateMessage(fileId, addedRecipients, removedRecipients, chunkIndexes, senderNodeId)
                }
                MessageType.FILE_PERMISSION_UPDATE_CONFIRMATION -> {
                    val nodeId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndexesLen = unpacker.unpackArrayHeader()
                    val chunkIndexes = List(chunkIndexesLen) { unpacker.unpackInt() }
                    val status = unpacker.unpackBoolean()
                    FilePermissionUpdateConfirmationMessage(nodeId, fileId, chunkIndexes, status)
                }
                MessageType.ECOSYSTEM_BROADCAST -> {
                    val broadcastId = unpacker.unpackString()
                    val senderId = unpacker.unpackString()
                    val messageType = unpacker.unpackString()
                    val payloadLen = unpacker.unpackBinaryHeader()
                    val payload = ByteArray(payloadLen)
                    unpacker.readPayload(payload)
                    EcosystemBroadcastMessage(broadcastId, senderId, messageType, payload)
                }
                MessageType.COMPUTE_TASK_REQUEST -> ComputeTaskRequestMessage.fromUnpacker(unpacker)
                MessageType.COMPUTE_NODE_RESPONSE -> ComputeNodeResponseMessage.fromUnpacker(unpacker)
                MessageType.TASK_COMPLETED -> TaskCompletedMessage.fromUnpacker(unpacker)
                MessageType.TASK_SCHEDULED -> TaskScheduledMessage.fromUnpacker(unpacker)
                MessageType.TASK_ASSIGNMENT -> TaskAssignmentMessage.fromUnpacker(unpacker)
                else -> throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            }
            unpacker.close()
            return message
        }
    }
}


data class ChunkTransferMessage(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val fileName: String,
    val relativePath: String,
    val chunkBytes: ByteArray,
    val hash: String,
    val replicaCount: Int = 0,
    val recipientKeyIds: List<Long> = emptyList(),
    val sessionKeys: Map<Long, ByteArray> = emptyMap(),
    val desiredReplicas: Int? = null
) : MeshEcosystemMessage("ChunkTransfer") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(chunkId)
        packer.packString(fileId)
        packer.packInt(chunkIndex)
        packer.packInt(totalChunks)
        packer.packString(fileName)
        packer.packString(relativePath)
        packer.packBinaryHeader(chunkBytes.size)
        packer.writePayload(chunkBytes)
        packer.packString(hash)
        packer.packInt(replicaCount)
        
        // Pack recipientKeyIds
        packer.packArrayHeader(recipientKeyIds.size)
        recipientKeyIds.forEach { packer.packLong(it) }
        
        // Pack sessionKeys
        packer.packMapHeader(sessionKeys.size)
        sessionKeys.forEach { (keyId, encryptedKey) ->
            packer.packLong(keyId)
            packer.packBinaryHeader(encryptedKey.size)
            packer.writePayload(encryptedKey)
        }
        
        // Pack desiredReplicas (nullable)
        if (desiredReplicas != null) {
            packer.packBoolean(true)
            packer.packInt(desiredReplicas)
        } else {
            packer.packBoolean(false)
        }
        
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalQueryMessage(val query: ChunkRetrievalQuery) : MeshEcosystemMessage("ChunkRetrievalQuery") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(query.fileId)
        
        // Pack chunkIndexes (nullable)
        if (query.chunkIndexes != null) {
            packer.packBoolean(true)
            packer.packArrayHeader(query.chunkIndexes.size)
            query.chunkIndexes.forEach { packer.packInt(it) }
        } else {
            packer.packBoolean(false)
        }
        
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalResponseMessage(
    val response: ChunkRetrievalResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("ChunkRetrievalResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packString(response.chunkId)
        packer.packString(response.fileId)
        packer.packInt(response.chunkIndex)
        packer.packInt(response.totalChunks)
        packer.packString(response.nodeId)
        packer.packString(response.fileName)
        packer.packString(response.relativePath)
        packer.packLong(response.chunkSize)
        packer.close()
        return packer.toByteArray()
    }
}

data class ReplicaQueryMessage(val query: ReplicaQuery) : MeshEcosystemMessage("ReplicaQuery") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(query.fileId)
        packer.close()
        return packer.toByteArray()
    }
}

data class ReplicaResponseMessage(
    val response: ReplicaResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("ReplicaResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packString(response.fileId)
        packer.close()
        return packer.toByteArray()
    }
}

// DEPRECATED: Storage capabilities superseded by OriginatorMessage with MeshRole.STORAGE
// data class StorageCapabilitiesMessage(val capabilities: StorageCapabilities) : MeshEcosystemMessage("StorageCapabilities") {
//     override fun toBytes(): ByteArray {
//         val packer = MessagePack.newDefaultBufferPacker()
//         packer.packString(type)
//         packer.packLong(capabilities.totalOffered)
//         packer.packLong(capabilities.currentlyUsed)
//         packer.packInt(capabilities.replicationFactor)
//         packer.packBoolean(capabilities.compressionSupported)
//         packer.packBoolean(capabilities.encryptionSupported)
//         packer.packArrayHeader(capabilities.accessPatterns.size)
//         capabilities.accessPatterns.forEach { packer.packString(it.name) }
//         packer.close()
//         return packer.toByteArray()
//     }
// }

data class FilePermissionUpdateMessage(
    val fileId: String,
    val addedRecipients: List<RecipientEntry>,
    val removedRecipients: List<RecipientEntry>,
    val chunkIndexes: List<Int>? = null,
    val senderNodeId: String
) : MeshEcosystemMessage("FilePermissionUpdateMessage") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(fileId)
        
        // Pack addedRecipients as JSON
        val addedJson = Json.encodeToString(addedRecipients)
        packer.packString(addedJson)
        
        // Pack removedRecipients as JSON
        val removedJson = Json.encodeToString(removedRecipients)
        packer.packString(removedJson)
        
        // Pack chunkIndexes (nullable)
        if (chunkIndexes != null) {
            packer.packBoolean(true)
            packer.packArrayHeader(chunkIndexes.size)
            chunkIndexes.forEach { packer.packInt(it) }
        } else {
            packer.packBoolean(false)
        }
        
        packer.packString(senderNodeId)
        packer.close()
        return packer.toByteArray()
    }
}

data class FilePermissionUpdateConfirmationMessage(
    val nodeId: String,
    val fileId: String,
    val chunkIndexes: List<Int>,
    val status: Boolean = true
) : MeshEcosystemMessage("FilePermissionUpdateConfirmation") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(nodeId)
        packer.packString(fileId)
        packer.packArrayHeader(chunkIndexes.size)
        chunkIndexes.forEach { packer.packInt(it) }
        packer.packBoolean(status)
        packer.close()
        return packer.toByteArray()
    }
}


/**
 * EcosystemBroadcastMessage: Canonical broadcast message for mesh-wide UDP gossip.
 */
data class EcosystemBroadcastMessage(
    val broadcastId: String,
    val senderId: String,
    val messageType: String,
    val payload: ByteArray,
    val metadata: Map<String, Any>? = null,
    val requestId: String? = null
) : MeshEcosystemMessage("EcosystemBroadcast") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(broadcastId)
        packer.packString(senderId)
        packer.packString(messageType)
        packer.packBinaryHeader(payload.size)
        packer.writePayload(payload)
        packer.close()
        return packer.toByteArray()
    }
}

data class ComputeTaskRequestMessage(
    val taskId: String,
    val serviceId: String,
    val inputParams: Map<String, Any>,
    val metadata: Map<String, Any>
) : MeshEcosystemMessage("ComputeTaskRequest") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(serviceId)
        // Serialize inputParams and metadata as JSON strings for simplicity
        packer.packString(Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), inputParams))
        packer.packString(Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), metadata))
        packer.close()
        return packer.toByteArray()
    }
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): ComputeTaskRequestMessage {
            val taskId = unpacker.unpackString()
            val serviceId = unpacker.unpackString()
            val inputParamsJson = unpacker.unpackString()
            val metadataJson = unpacker.unpackString()
            val inputParams = Json.decodeFromString(MapSerializer(String.serializer(), AnySerializer), inputParamsJson)
            val metadata = Json.decodeFromString(MapSerializer(String.serializer(), AnySerializer), metadataJson)
            return ComputeTaskRequestMessage(taskId, serviceId, inputParams, metadata)
        }
    }
}

/**
 * ComputeNodeResponseMessage: Response from a node when it receives a compute task request.
 * Includes ML Kit capabilities and node performance metrics for task assignment decisions.
 */
data class ComputeNodeResponseMessage(
    val requestId: String,
    val response: ComputeNodeResponse
) : MeshEcosystemMessage("ComputeNodeResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId)
        // Pack response fields
        packer.packInt(response.nodeAddress)
        packer.packBoolean(response.available)
        packer.packLong(response.estimatedLatencyMs)
        packer.packFloat(response.currentLoad)
        // Pack ML Kit capabilities
        packer.packArrayHeader(response.mlKitFeatures.size)
        response.mlKitFeatures.forEach { packer.packString(it) }
        packer.packBoolean(response.mlKitCustomSupport)
        packer.packString(response.requestId)
        packer.packLong(response.timestamp)
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): ComputeNodeResponseMessage {
            val requestId = unpacker.unpackString()
            val nodeAddress = unpacker.unpackInt()
            val available = unpacker.unpackBoolean()
            val estimatedLatencyMs = unpacker.unpackLong()
            val currentLoad = unpacker.unpackFloat()
            val mlKitFeatures = List(unpacker.unpackArrayHeader()) { unpacker.unpackString() }
            val mlKitCustomSupport = unpacker.unpackBoolean()
            val responseRequestId = unpacker.unpackString()
            val timestamp = unpacker.unpackLong()
            
            val computeResponse = ComputeNodeResponse(
                nodeAddress = nodeAddress,
                available = available,
                estimatedLatencyMs = estimatedLatencyMs,
                currentLoad = currentLoad,
                mlKitFeatures = mlKitFeatures,
                mlKitCustomSupport = mlKitCustomSupport,
                requestId = responseRequestId,
                timestamp = timestamp
            )
            return ComputeNodeResponseMessage(requestId, computeResponse)
        }
    }
}

/**
 * TaskCompletedMessage: Notification that a distributed task has completed execution.
 * Includes execution statistics, error information if failed, and result storage references.
 * 
 * Phase 1: Task Execution Layer - Task completion notification protocol
 */
data class TaskCompletedMessage(
    val taskId: String,
    val executorNodeId: String,
    val status: String,  // "SUCCESS", "FAILED", "TIMEOUT"
    val executionStats: ExecutionStats,
    val executionError: ExecutionError? = null,
    val resultStorageRefs: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) : MeshEcosystemMessage("TaskCompleted") {
    
    data class ExecutionStats(
        val executionTimeMs: Long,
        val cpuTimeMs: Long,
        val memoryPeakBytes: Long,
        val diskReadBytes: Long,
        val diskWriteBytes: Long,
        val networkSentBytes: Long = 0L,
        val networkRecvBytes: Long = 0L
    )
    
    data class ExecutionError(
        val errorType: String,  // Maps to ExecutionErrorType
        val errorMessage: String,
        val errorCode: Int = 0,
        val stackTrace: String? = null
    )
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(executorNodeId)
        packer.packString(status)
        
        // Pack execution stats
        packer.packLong(executionStats.executionTimeMs)
        packer.packLong(executionStats.cpuTimeMs)
        packer.packLong(executionStats.memoryPeakBytes)
        packer.packLong(executionStats.diskReadBytes)
        packer.packLong(executionStats.diskWriteBytes)
        packer.packLong(executionStats.networkSentBytes)
        packer.packLong(executionStats.networkRecvBytes)
        
        // Pack execution error (nullable)
        if (executionError != null) {
            packer.packBoolean(true)
            packer.packString(executionError.errorType)
            packer.packString(executionError.errorMessage)
            packer.packInt(executionError.errorCode)
            packer.packString(executionError.stackTrace ?: "")
        } else {
            packer.packBoolean(false)
        }
        
        // Pack result storage references
        packer.packArrayHeader(resultStorageRefs.size)
        resultStorageRefs.forEach { packer.packString(it) }
        
        packer.packLong(timestamp)
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskCompletedMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackString()
            val status = unpacker.unpackString()
            
            // Unpack execution stats
            val executionStats = ExecutionStats(
                executionTimeMs = unpacker.unpackLong(),
                cpuTimeMs = unpacker.unpackLong(),
                memoryPeakBytes = unpacker.unpackLong(),
                diskReadBytes = unpacker.unpackLong(),
                diskWriteBytes = unpacker.unpackLong(),
                networkSentBytes = unpacker.unpackLong(),
                networkRecvBytes = unpacker.unpackLong()
            )
            
            // Unpack execution error (nullable)
            val executionError = if (unpacker.unpackBoolean()) {
                ExecutionError(
                    errorType = unpacker.unpackString(),
                    errorMessage = unpacker.unpackString(),
                    errorCode = unpacker.unpackInt(),
                    stackTrace = unpacker.unpackString().takeIf { it.isNotEmpty() }
                )
            } else null
            
            // Unpack result storage references
            val resultStorageRefs = List(unpacker.unpackArrayHeader()) {
                unpacker.unpackString()
            }
            
            val timestamp = unpacker.unpackLong()
            
            return TaskCompletedMessage(
                taskId, executorNodeId, status, executionStats, 
                executionError, resultStorageRefs, timestamp
            )
        }
    }
}

/**
 * TaskScheduledMessage: Notification that a task has been scheduled for execution on a specific node.
 * Sent from orchestrator/scheduler to executor node and requester for task tracking.
 * 
 * Phase 1: Message Protocol Extensions - Task scheduling notification
 */
data class TaskScheduledMessage(
    val taskId: String,
    val executorNodeId: String,
    val requesterNodeId: String,
    val scheduledAt: Long = System.currentTimeMillis(),
    val estimatedStartTime: Long? = null,
    val taskPriority: String = "NORMAL",  // BACKGROUND, NORMAL, HIGH, CRITICAL
    val resourceAllocation: Map<String, Any> = emptyMap(),
    val metadata: Map<String, Any>? = null,
    val requestId: String? = null
) : MeshEcosystemMessage("TaskScheduled") {
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(executorNodeId)
        packer.packString(requesterNodeId)
        packer.packLong(scheduledAt)
        
        // Pack estimatedStartTime (nullable)
        if (estimatedStartTime != null) {
            packer.packBoolean(true)
            packer.packLong(estimatedStartTime)
        } else {
            packer.packBoolean(false)
        }
        
        packer.packString(taskPriority)
        
        // Pack resource allocation as JSON
        val resourceJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), resourceAllocation)
        packer.packString(resourceJson)
        
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskScheduledMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackString()
            val requesterNodeId = unpacker.unpackString()
            val scheduledAt = unpacker.unpackLong()
            
            // Unpack estimatedStartTime (nullable)
            val estimatedStartTime = if (unpacker.unpackBoolean()) {
                unpacker.unpackLong()
            } else null
            
            val taskPriority = unpacker.unpackString()
            
            // Unpack resource allocation from JSON
            val resourceJson = unpacker.unpackString()
            val resourceAllocation = Json.decodeFromString(
                MapSerializer(String.serializer(), AnySerializer), 
                resourceJson
            )
            
            return TaskScheduledMessage(
                taskId, executorNodeId, requesterNodeId,
                scheduledAt, estimatedStartTime, taskPriority, resourceAllocation
            )
        }
    }
}

/**
 * TaskAssignmentMessage: Complete task assignment sent from scheduler to executor node.
 * Includes all parameters needed for task execution: execution context, resource limits, input files.
 * 
 * Phase 1: Message Protocol Extensions - Comprehensive task assignment
 */
data class TaskAssignmentMessage(
    val taskId: String,
    val executorNodeId: String,
    val requesterNodeId: String,
    val taskType: String,  // PYTHON, JAVA, JVM, JAVASCRIPT, ML_NATIVE, WORKFLOW
    val jobType: String,   // IMAGE_PROCESSING, VIDEO_PROCESSING, DATA_ANALYSIS, etc.
    val executionContext: Map<String, Any>,  // Includes working directory, environment vars, etc.
    val resourceLimits: Map<String, Any>,    // Memory, CPU, disk, network, timeout
    val inputFiles: List<Map<String, String>>,  // List of {fileId, storageRef, accessScope}
    val outputRequirements: Map<String, Any>,  // Output destination, permissions, etc.
    val priority: String = "NORMAL",
    val assignedAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any>? = null,
    val requestId: String? = null
) : MeshEcosystemMessage("TaskAssignment") {
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(executorNodeId)
        packer.packString(requesterNodeId)
        packer.packString(taskType)
        packer.packString(jobType)
        
        // Pack execution context as JSON
        val contextJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), executionContext)
        packer.packString(contextJson)
        
        // Pack resource limits as JSON
        val limitsJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), resourceLimits)
        packer.packString(limitsJson)
        
        // Pack input files array
        packer.packArrayHeader(inputFiles.size)
        inputFiles.forEach { fileMap ->
            val fileJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), fileMap)
            packer.packString(fileJson)
        }
        
        // Pack output requirements as JSON
        val outputJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), outputRequirements)
        packer.packString(outputJson)
        
        packer.packString(priority)
        packer.packLong(assignedAt)
        
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskAssignmentMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackString()
            val requesterNodeId = unpacker.unpackString()
            val taskType = unpacker.unpackString()
            val jobType = unpacker.unpackString()
            
            // Unpack execution context from JSON
            val contextJson = unpacker.unpackString()
            val executionContext = Json.decodeFromString(
                MapSerializer(String.serializer(), AnySerializer),
                contextJson
            )
            
            // Unpack resource limits from JSON
            val limitsJson = unpacker.unpackString()
            val resourceLimits = Json.decodeFromString(
                MapSerializer(String.serializer(), AnySerializer),
                limitsJson
            )
            
            // Unpack input files array
            val inputFiles = List(unpacker.unpackArrayHeader()) {
                val fileJson = unpacker.unpackString()
                Json.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    fileJson
                )
            }
            
            // Unpack output requirements from JSON
            val outputJson = unpacker.unpackString()
            val outputRequirements = Json.decodeFromString(
                MapSerializer(String.serializer(), AnySerializer),
                outputJson
            )
            
            val priority = unpacker.unpackString()
            val assignedAt = unpacker.unpackLong()
            
            return TaskAssignmentMessage(
                taskId, executorNodeId, requesterNodeId, taskType, jobType,
                executionContext, resourceLimits, inputFiles, outputRequirements,
                priority, assignedAt
            )
        }
    }
}

