package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.StorageNodeRequest
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalQuery
import com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.vnet.ReplicaQuery
import com.ustadmobile.meshrabiya.vnet.ReplicaResponse
// DEPRECATED: Storage advertisement superseded by OriginatorMessage with MeshRole.STORAGE
// import com.ustadmobile.meshrabiya.mmcp.StorageCapabilities
// import com.ustadmobile.meshrabiya.mmcp.AccessPattern
import com.ustadmobile.meshrabiya.service.AccessType
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
                    response = StorageNodeResponse(
                        nodeId = unpacker.unpackString(),
                        availableSpace = unpacker.unpackLong(),
                        systemState = unpacker.unpackString(),
                        url = unpacker.unpackString(),
                        latency = unpacker.unpackInt(),
                        fitnessScore = unpacker.unpackFloat()
                    ),
                    requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
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
        packer.packString(response.nodeId)
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
    val resourceAllocation: Map<String, Any> = emptyMap()
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
    val assignedAt: Long = System.currentTimeMillis()
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

