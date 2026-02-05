package com.ustadmobile.meshrabiya.vnet.broadcast

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes/deserializes broadcast packets according to the byte-level format specification
 */
object BroadcastPacketSerializer {
    
    private const val VERSION = 1
    
    /**
     * Serialize a broadcast message+chunk into packet payload bytes
     * 
     * Format:
     * [0-3]: Version (Int32BE) = 1
     * [4-7]: Broadcast ID length (Int32)
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
                       4 + broadcastIdBytes.size + // broadcastId
                       4 + messageBytes.size + // message
                       4 + metadataBytes.size + // metadata
                       chunkData.size // chunk data
        
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        
        // Version
        buffer.putInt(VERSION)
        
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
}
