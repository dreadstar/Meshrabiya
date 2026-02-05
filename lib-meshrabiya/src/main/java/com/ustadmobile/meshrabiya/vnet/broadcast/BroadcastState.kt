package com.ustadmobile.meshrabiya.vnet.broadcast

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks state of an in-progress broadcast send operation
 */
data class OutgoingBroadcastState(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val totalChunks: Int,
    var chunksSent: Int = 0,
    val callback: (Result<com.ustadmobile.meshrabiya.api.model.BroadcastResultDto>) -> Unit,
    val startTime: Long = System.currentTimeMillis()
)

/**
 * Tracks state of an in-progress broadcast receive/reassembly
 */
data class IncomingBroadcastState(
    val broadcastId: String,
    val messageText: String,
    val metadata: BroadcastChunkMetadata,
    val senderNodeId: Int,
    val receivedChunks: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
    val startTime: Long = System.currentTimeMillis()
) {
    /**
     * Check if all chunks have been received
     */
    fun isComplete(): Boolean = receivedChunks.size == metadata.totalChunks
    
    /**
     * Get missing chunk indices
     */
    fun getMissingChunks(): List<Int> {
        return (0 until metadata.totalChunks).filter { it !in receivedChunks.keys }
    }
    
    /**
     * Reassemble chunks into complete file bytes
     */
    fun reassemble(): ByteArray {
        require(isComplete()) { "Cannot reassemble incomplete broadcast" }
        
        val result = ByteArray(metadata.totalFileSize.toInt())
        var offset = 0
        
        for (i in 0 until metadata.totalChunks) {
            val chunkData = receivedChunks[i] ?: error("Missing chunk $i")
            chunkData.copyInto(result, offset)
            offset += chunkData.size
        }
        
        return result
    }
}
