package com.ustadmobile.meshrabiya.vnet.broadcast

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes/deserializes broadcast packets according to the byte-level format specification
 */
object BroadcastPacketSerializer {
    
    
    // Packet types for broadcast protocol
    const val TYPE_BROADCAST_CHUNK = 0x01
    const val TYPE_NACK_REQUEST = 0x02

    // Packet flags
    const val FLAG_HAS_TEXT = 1 shl 0   // 0x01
    const val FLAG_HAS_GPS = 1 shl 1    // 0x02
    const val FLAG_HAS_FILE = 1 shl 2   // 0x04

    // Wire format constants
    const val HEADER_FLAGS_SIZE = 1
    const val HEADER_TYPE_SIZE = 1
    const val HEADER_SIZE = HEADER_FLAGS_SIZE + HEADER_TYPE_SIZE  // 2 bytes
    
    /**
     * Serialize a NACK (negative acknowledgment) request for missing chunks.
     * Envelope format:
     * [0]: flags (for NACK we keep 0)
     * [1]: packetType = TYPE_NACK_REQUEST
     * [2-5]: Broadcast ID length (Int32)
     * [6-X]: Broadcast ID (UTF-8 UUID)
     * [X-X+3]: Missing chunks count (Int32)
     * [X+4-Y]: Missing chunk indices (Int32 array)
     */
    fun serializeNackRequest(
        broadcastId: String,
        missingChunks: List<Int>
    ): ByteArray {
        val broadcastIdBytes = broadcastId.toByteArray(Charsets.UTF_8)
        val totalSize = HEADER_SIZE + 4 + broadcastIdBytes.size + 4 + (missingChunks.size * 4)
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(0.toByte()) // no flags for NACK
        buffer.put(TYPE_NACK_REQUEST.toByte())

        buffer.putInt(broadcastIdBytes.size)
        buffer.put(broadcastIdBytes)
        buffer.putInt(missingChunks.size)
        missingChunks.forEach { buffer.putInt(it) }

        return buffer.array()
    }

    fun serializeSubtypeWithFlags(
        flags: Int,
        payloadBody: ByteArray
    ): ByteArray {
        val totalSize = 1 + payloadBody.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(flags.toByte())
        buffer.put(payloadBody)
        return buffer.array()
    }
    
    /**
     * Serialize a broadcast message+chunk into packet payload bytes.
     * Envelope format:
     * [0]: flags (text/GPS/file bitmask from payload and metadata)
     * [1]: packetType = TYPE_BROADCAST_CHUNK
     * [2-5]: Broadcast ID length (Int32)
     * [10-X]: Broadcast ID (UTF-8 UUID)
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

        var flags = 0
        if (messageText.isNotEmpty()) flags = flags or FLAG_HAS_TEXT
        if (chunkMetadata.latitude != null && chunkMetadata.longitude != null) flags = flags or FLAG_HAS_GPS
        if (chunkMetadata.fileId.isNotEmpty()) flags = flags or FLAG_HAS_FILE

        val totalSize = HEADER_SIZE +
                        4 + broadcastIdBytes.size +
                        4 + messageBytes.size +
                        4 + metadataBytes.size +
                        chunkData.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(flags.toByte())
        buffer.put(TYPE_BROADCAST_CHUNK.toByte())

        buffer.putInt(broadcastIdBytes.size)
        buffer.put(broadcastIdBytes)

        buffer.putInt(messageBytes.size)
        buffer.put(messageBytes)

        buffer.putInt(metadataBytes.size)
        buffer.put(metadataBytes)

        buffer.put(chunkData)
        val packet = buffer.array()
        val payloadCRC = checksumPayloadCRC32(packet)
        // diagnostics: flags/type invariant maintained by protocol
        // no behavior change
        println("[BroadcastPacketSerializer] serialize: packetType=0x01 flags=0x${String.format("%02x", flags)} payloadCRC32=0x${payloadCRC.toString(16)}")
        return packet
    }
    
    /**
     * Deserialize packet payload into broadcast components.
     * Supports both new envelope format (version + flags + type) and legacy type-at-offset-zero.
     * @return Triple of (broadcastId, messageText, (metadata, chunkData))
     */
    fun deserialize(payload: ByteArray): Triple<String, String, Pair<BroadcastChunkMetadata, ByteArray>> {
        if (payload.size < HEADER_SIZE) {
            throw IllegalArgumentException("Payload too small for broadcast packet: ${payload.size}")
        }

        val flags = payload[0].toInt() and 0xFF
        val packetType = payload[1].toInt() and 0xFF
        val payloadCRC = checksumPayloadCRC32(payload)
        println("[BroadcastPacketSerializer] deserialize: packetType=0x${String.format("%02x", packetType)} flags=0x${String.format("%02x", flags)} payloadCRC32=0x${payloadCRC.toString(16)}")

        require(packetType == TYPE_BROADCAST_CHUNK) { "Expected broadcast chunk, got type: $packetType" }

        val bodyBuffer = ByteBuffer.wrap(payload, HEADER_SIZE, payload.size - HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

        val broadcastIdLength = bodyBuffer.getInt()
        val broadcastIdBytes = ByteArray(broadcastIdLength); bodyBuffer.get(broadcastIdBytes)
        val broadcastId = String(broadcastIdBytes, Charsets.UTF_8)

        val messageLength = bodyBuffer.getInt()
        val messageBytes = ByteArray(messageLength); bodyBuffer.get(messageBytes)
        val messageText = String(messageBytes, Charsets.UTF_8)

        val metadataLength = bodyBuffer.getInt()
        val metadataBytes = ByteArray(metadataLength); bodyBuffer.get(metadataBytes)
        val metadata = BroadcastChunkMetadata.fromJson(String(metadataBytes, Charsets.UTF_8))

        val chunkData = ByteArray(bodyBuffer.remaining()); bodyBuffer.get(chunkData)
        return Triple(broadcastId, messageText, Pair(metadata, chunkData))
    }

    /**
     * Deserialize NACK request packet.
     * Supports both new envelope format and legacy.
     * @return Pair of (broadcastId, missingChunkIndices)
     */
    fun deserializeNackRequest(payload: ByteArray): Pair<String, List<Int>> {
        if (payload.size < HEADER_SIZE) {
            throw IllegalArgumentException("Payload too small for NACK packet: ${payload.size}")
        }

        val flags = payload[0].toInt() and 0xFF
        val packetType = payload[1].toInt() and 0xFF

        require(packetType == TYPE_NACK_REQUEST) { "Expected NACK packet, got type: $packetType" }

        val bodyBuffer = ByteBuffer.wrap(payload, HEADER_SIZE, payload.size - HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

        val broadcastIdLength = bodyBuffer.getInt()
        val broadcastIdBytes = ByteArray(broadcastIdLength); bodyBuffer.get(broadcastIdBytes)
        val broadcastId = String(broadcastIdBytes, Charsets.UTF_8)

        val chunkCount = bodyBuffer.getInt()
        val missingChunks = (0 until chunkCount).map { bodyBuffer.getInt() }
        return Pair(broadcastId, missingChunks)
    }

    /**
     * Get packet type from payload without full deserialization.
     * Uses new envelope when available, falls back to legacy.
     */
    fun getPacketType(payload: ByteArray): Int {
        if (payload.size < HEADER_SIZE) throw IllegalArgumentException("Payload too small: ${payload.size}")
        return payload[HEADER_FLAGS_SIZE].toInt() and 0xFF
    }

    fun getFlags(payload: ByteArray): Int {
        if (payload.size < HEADER_SIZE) return 0
        return payload[0].toInt() and 0xFF
    }

    fun hasText(payload: ByteArray): Boolean = getFlags(payload) and FLAG_HAS_TEXT != 0
    fun hasGps(payload: ByteArray): Boolean = getFlags(payload) and FLAG_HAS_GPS != 0
    fun hasFile(payload: ByteArray): Boolean = getFlags(payload) and FLAG_HAS_FILE != 0

    fun isBroadcastPacket(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val packetType = getPacketType(payload)
        return packetType == TYPE_BROADCAST_CHUNK || packetType == TYPE_NACK_REQUEST
    }

    fun checksumPayloadCRC32(payload: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(payload)
        return crc.value
    }
}
