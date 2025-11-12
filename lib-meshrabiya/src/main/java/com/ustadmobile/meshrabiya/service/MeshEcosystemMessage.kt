package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.storage.StorageNodeRequest
import com.ustadmobile.meshrabiya.storage.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalQuery
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.vnet.ReplicaQuery
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
import com.ustadmobile.meshrabiya.storage.StorageCapabilities
import com.ustadmobile.meshrabiya.service.MeshGossipService.AccessType
import com.ustadmobile.meshrabiya.storage.AccessPattern
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.core.MessageBufferPacker

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
                "StorageNodeRequest" -> StorageNodeRequestMessage(
                    StorageNodeRequest(
                        requiredSpace = unpacker.unpackLong(),
                        fileName = unpacker.unpackString(),
                        fileId = unpacker.unpackString().ifEmpty { null },
                        senderId = unpacker.unpackString()
                    )
                )
                "StorageNodeResponse" -> StorageNodeResponseMessage(
                    StorageNodeResponse(
                        nodeId = unpacker.unpackString(),
                        availableSpace = unpacker.unpackLong(),
                        systemState = unpacker.unpackString(),
                        url = unpacker.unpackString(),
                        latency = unpacker.unpackInt(),
                        fitnessScore = unpacker.unpackFloat()
                    )
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
                    ChunkTransferMessage(
                        chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash
                    )
                }
                "ChunkRetrievalQuery" -> ChunkRetrievalQueryMessage(
                    ChunkRetrievalQuery(unpacker.unpackString())
                )
                "ChunkRetrievalResponse" -> ChunkRetrievalResponseMessage(
                    ChunkRetrievalResponse(
                        chunkId = unpacker.unpackString(),
                        fileId = unpacker.unpackString(),
                        chunkIndex = unpacker.unpackInt(),
                        totalChunks = unpacker.unpackInt(),
                        nodeId = unpacker.unpackString(),
                        fileName = unpacker.unpackString(),
                        relativePath = unpacker.unpackString(),
                        chunkSize = unpacker.unpackLong()
                    )
                )
                "ReplicaQuery" -> ReplicaQueryMessage(
                    ReplicaQuery(unpacker.unpackString())
                )
                "ReplicaResponse" -> ReplicaResponseMessage(
                    ReplicaResponse(unpacker.unpackString())
                )
                "StorageCapabilities" -> StorageCapabilitiesMessage(
                    StorageCapabilities(
                        totalOffered = unpacker.unpackLong(),
                        currentlyUsed = unpacker.unpackLong(),
                        replicationFactor = unpacker.unpackInt(),
                        compressionSupported = unpacker.unpackBoolean(),
                        encryptionSupported = unpacker.unpackBoolean(),
                        accessPatterns = List(unpacker.unpackArrayHeader()) { AccessPattern.valueOf(unpacker.unpackString()) }.toSet()
                    )
                )
                "FilePermissionUpdateMessage" -> FilePermissionUpdateMessage(
                    fileId = unpacker.unpackString(),
                    newRecipientKeyIds = List(unpacker.unpackArrayHeader()) { unpacker.unpackLong() },
                    senderNodeId = unpacker.unpackString()
                )
                "FilePermissionUpdateConfirmation" -> FilePermissionUpdateConfirmationMessage(
                    fileId = unpacker.unpackString(),
                    chunkId = unpacker.unpackString(),
                    storageNodeId = unpacker.unpackString(),
                    status = unpacker.unpackBoolean()
                )
                "TaskDataAccessUpdateMessage" -> TaskDataAccessUpdateMessage(
                    fileId = unpacker.unpackString(),
                    affectedTaskUUIDs = List(unpacker.unpackArrayHeader()) { unpacker.unpackString() },
                    accessType = AccessType.valueOf(unpacker.unpackString())
                )
                "EcosystemBroadcast" -> EcosystemBroadcastMessage(
                    broadcastId = unpacker.unpackString(),
                    senderId = unpacker.unpackString(),
                    messageType = unpacker.unpackString(),
                    payload = unpacker.readPayload(unpacker.unpackBinaryHeader())
                )
                 "ComputeTaskRequest" -> ComputeTaskRequestMessage.fromUnpacker(unpacker)
                 "ComputeNodeResponse" -> ComputeNodeResponseMessage.fromUnpacker(unpacker)
                else -> throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            }
            unpacker.close()
            return message
        }
    }
}

// === Message Subclasses ===

data class StorageNodeRequestMessage(val request: StorageNodeRequest) : MeshEcosystemMessage("StorageNodeRequest") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packLong(request.requiredSpace)
        packer.packString(request.fileName)
        packer.packString(request.fileId ?: "")
        packer.packString(request.senderId)
        packer.close()
        return packer.toByteArray()
    }
}

data class StorageNodeResponseMessage(val response: StorageNodeResponse) : MeshEcosystemMessage("StorageNodeResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(response.nodeId)
        packer.packLong(response.availableSpace)
        packer.packString(response.systemState)
        packer.packString(response.url)
        packer.packInt(response.latency)
        packer.packFloat(response.fitnessScore)
        packer.close()
        return packer.toByteArray()
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
    val hash: String
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
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalQueryMessage(val query: ChunkRetrievalQuery) : MeshEcosystemMessage("ChunkRetrievalQuery") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(query.fileId)
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalResponseMessage(val response: ChunkRetrievalResponse) : MeshEcosystemMessage("ChunkRetrievalResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
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

data class ReplicaResponseMessage(val response: ReplicaResponse) : MeshEcosystemMessage("ReplicaResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(response.nodeId)
        packer.close()
        return packer.toByteArray()
    }
}

data class StorageCapabilitiesMessage(val capabilities: StorageCapabilities) : MeshEcosystemMessage("StorageCapabilities") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packLong(capabilities.totalOffered)
        packer.packLong(capabilities.currentlyUsed)
        packer.packInt(capabilities.replicationFactor)
        packer.packBoolean(capabilities.compressionSupported)
        packer.packBoolean(capabilities.encryptionSupported)
        packer.packArrayHeader(capabilities.accessPatterns.size)
        capabilities.accessPatterns.forEach { packer.packString(it.name) }
        packer.close()
        return packer.toByteArray()
    }
}

data class FilePermissionUpdateMessage(
    val fileId: String,
    val newRecipientKeyIds: List<Long>,
    val senderNodeId: String
) : MeshEcosystemMessage("FilePermissionUpdateMessage") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(fileId)
        packer.packArrayHeader(newRecipientKeyIds.size)
        newRecipientKeyIds.forEach { packer.packLong(it) }
        packer.packString(senderNodeId)
        packer.close()
        return packer.toByteArray()
    }
}

data class FilePermissionUpdateConfirmationMessage(
    val fileId: String,
    val chunkId: String,
    val storageNodeId: String,
    val status: Boolean
) : MeshEcosystemMessage("FilePermissionUpdateConfirmation") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(fileId)
        packer.packString(chunkId)
        packer.packString(storageNodeId)
        packer.packBoolean(status)
        packer.close()
        return packer.toByteArray()
    }
}

data class TaskDataAccessUpdateMessage(
    val fileId: String,
    val affectedTaskUUIDs: List<String>,
    val accessType: AccessType
) : MeshEcosystemMessage("TaskDataAccessUpdateMessage") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(fileId)
        packer.packArrayHeader(affectedTaskUUIDs.size)
        affectedTaskUUIDs.forEach { packer.packString(it) }
        packer.packString(accessType.name)
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
    val payload: ByteArray
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
