package com.ustadmobile.meshrabiya.vnet.broadcast

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes/deserializes broadcast packets according to the byte-level format specification
 */
object BroadcastPacketSerializer {
    
    private const val VERSION = 1
    
    // Packet types for NACK protocol
    const val TYPE_BROADCAST_CHUNK = 0x01
    const val TYPE_NACK_REQUEST = 0x02
    
    /**
     * Serialize a NACK (negative acknowledgment) request for missing chunks
     * 
     * Format:
     * [0-3]: Version (Int32BE) = 1
     * [4]: Packet Type (Byte) = TYPE_NACK_REQUEST
     * [5-8]: Broadcast ID length (Int32)
     * [9-X]: Broadcast ID (UTF-8 UUID)
     * [X-X+3]: Missing chunks count (Int32)
     * [X+4-Y]: Missing chunk indices (Int32 array)
     */
    fun serializeNackRequest(
        broadcastId: String,
        missingChunks: List<Int>
    ): ByteArray {
        val broadcastIdBytes = broadcastId.toByteArray(Charsets.UTF_8)
        val totalSize = 4 + 1 + 4 + broadcastIdBytes.size + 4 + (missingChunks.size * 4)
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        
        buffer.putInt(VERSION)
        buffer.put(TYPE_NACK_REQUEST.toByte())
        buffer.putInt(broadcastIdBytes.size)
        buffer.put(broadcastIdBytes)
        buffer.putInt(missingChunks.size)
        missingChunks.forEach { buffer.putInt(it) }
        
        return buffer.array()
    }
    
    /**
     * Serialize a broadcast message+chunk into packet payload bytes
     * 
     * Format:
     * [0-3]: Version (Int32BE) = 1
     * [4]: Packet Type (Byte) = TYPE_BROADCAST_CHUNK
     * [5-8]: Broadcast ID length (Int32)
     * [8-X]: Broadcast ID (UTF-8 UUID)
     * [X-X+3]: Message length (Int32)
     * [X+4-Y]: Message text (UTF-8)
     * [Y-Y+3]: Chunk metadata length (Int32)
     * [Y+4-Z]: Chunk metadata (JSON)
     * [Z+1-W]: Chunk data bytes
     */
    fun serialize(
        broadcastId: String,
        messageText: String,
        chunkMetadata: BroadcastChunkMetadata,
        chunkData: ByteArray
    ): ByteArray {
        require(messageText.length <= MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            "Message exceeds max length: ${messageText.length} > ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH}"
        }
        
        val broadcastIdBytes = broadcastId.toByteArray(Charsets.UTF_8)
        val messageBytes = messageText.toByteArray(Charsets.UTF_8)
        val metadataBytes = chunkMetadata.toJson().toByteArray(Charsets.UTF_8)
        
        val totalSize = 4 + // version
                       1 + // packet type
                       4 + broadcastIdBytes.size + // broadcastId
                       4 + messageBytes.size + // message
                       4 + metadataBytes.size + // metadata
                       chunkData.size // chunk data
        
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        
        // Version
        buffer.putInt(VERSION)
        
        // Packet type
        buffer.put(TYPE_BROADCAST_CHUNK.toByte())
        
        // Broadcast ID
        buffer.putInt(broadcastIdBytes.size)
        buffer.put(broadcastIdBytes)
        
        // Message
        buffer.putInt(messageBytes.size)
        buffer.put(messageBytes)
        
        // Metadata
        buffer.putInt(metadataBytes.size)
        buffer.put(metadataBytes)
        
        // Chunk data
        buffer.put(chunkData)
        
        return buffer.array()
    }
    
    /**
     * Deserialize packet payload into broadcast components
     * 
     * @return Triple of (broadcastId, messageText, (metadata, chunkData))
     */
    fun deserialize(payload: ByteArray): Triple<String, String, Pair<BroadcastChunkMetadata, ByteArray>> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        
        // Version check
        val version = buffer.getInt()
        require(version == VERSION) { "Unsupported broadcast packet version: $version" }
        
        // Packet type check
        val packetType = buffer.get().toInt()
        require(packetType == TYPE_BROADCAST_CHUNK) { "Expected broadcast chunk, got type: $packetType" }
        
        // Broadcast ID
        val broadcastIdLength = buffer.getInt()
        val broadcastIdBytes = ByteArray(broadcastIdLength)
        buffer.get(broadcastIdBytes)
        val broadcastId = String(broadcastIdBytes, Charsets.UTF_8)
        
        // Message
        val messageLength = buffer.getInt()
        val messageBytes = ByteArray(messageLength)
        buffer.get(messageBytes)
        val messageText = String(messageBytes, Charsets.UTF_8)
        
        // Metadata
        val metadataLength = buffer.getInt()
        val metadataBytes = ByteArray(metadataLength)
        buffer.get(metadataBytes)
        val metadataJson = String(metadataBytes, Charsets.UTF_8)
        val metadata = BroadcastChunkMetadata.fromJson(metadataJson)
        
        // Chunk data (rest of buffer)
        val chunkData = ByteArray(buffer.remaining())
        buffer.get(chunkData)
        
        return Triple(broadcastId, messageText, Pair(metadata, chunkData))
    }
    
    /**
     * Deserialize NACK request packet
     * 
     * @return Pair of (broadcastId, missingChunkIndices)
     */
    fun deserializeNackRequest(payload: ByteArray): Pair<String, List<Int>> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        
        // Version check
        val version = buffer.getInt()
        require(version == VERSION) { "Unsupported NACK packet version: $version" }
        
        // Packet type check
        val packetType = buffer.get().toInt()
        require(packetType == TYPE_NACK_REQUEST) { "Expected NACK packet, got type: $packetType" }
        
        // Broadcast ID
        val broadcastIdLength = buffer.getInt()
        val broadcastIdBytes = ByteArray(broadcastIdLength)
        buffer.get(broadcastIdBytes)
        val broadcastId = String(broadcastIdBytes, Charsets.UTF_8)
        
        // Missing chunks
        val chunkCount = buffer.getInt()
        val missingChunks = (0 until chunkCount).map { buffer.getInt() }
        
        return Pair(broadcastId, missingChunks)
    }
    
    /**
     * Get packet type from payload without full deserialization
     * Useful for routing packets before processing
     */
    fun getPacketType(payload: ByteArray): Int {
        if (payload.size < 5) {
            throw IllegalArgumentException("Payload too small to determine packet type")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        
        // Skip version (4 bytes)
        buffer.getInt()
        
        // Read packet type (1 byte)
        return buffer.get().toInt()
    }
}
