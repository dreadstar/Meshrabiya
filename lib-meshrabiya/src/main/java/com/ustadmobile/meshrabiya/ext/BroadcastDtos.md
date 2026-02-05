package com.ustadmobile.meshrabiya.ext

import java.util.UUID

/**
 * Result of broadcast send operation
 */
data class BroadcastResultDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val totalChunks: Int,
    val successNodeIds: List<Int>,  // Nodes that acknowledged receipt
    val failedNodeIds: List<Int>,   // Nodes that failed or timed out
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Notification of received broadcast
 */
data class BroadcastReceivedDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val filePath: String,  // Path in Shared/ folder
    val senderNodeId: Int,
    val receivedAt: Long = System.currentTimeMillis(),
    val hasError: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Progress update during broadcast send
 */
data class BroadcastProgressDto(
    val broadcastId: String,
    val chunksSent: Int,
    val totalChunks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long
)

/**
 * Internal metadata for chunk in transit
 */
data class BroadcastChunkMetadata(
    val chunkId: String,
    val fileId: String,
    val fileName: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val totalFileSize: Long,
    val hash: String
) {
    fun toJson(): String {
        return """{"chunkId":"$chunkId","fileId":"$fileId","fileName":"$fileName","chunkIndex":$chunkIndex,"totalChunks":$totalChunks,"chunkSize":$chunkSize,"totalFileSize":$totalFileSize,"hash":"$hash"}"""
    }
    
    companion object {
        fun fromJson(json: String): BroadcastChunkMetadata {
            // Simple JSON parsing (or use kotlinx.serialization)
            val map = parseSimpleJson(json)
            return BroadcastChunkMetadata(
                chunkId = map["chunkId"] ?: error("Missing chunkId"),
                fileId = map["fileId"] ?: error("Missing fileId"),
                fileName = map["fileName"] ?: error("Missing fileName"),
                chunkIndex = map["chunkIndex"]?.toInt() ?: error("Missing chunkIndex"),
                totalChunks = map["totalChunks"]?.toInt() ?: error("Missing totalChunks"),
                chunkSize = map["chunkSize"]?.toLong() ?: error("Missing chunkSize"),
                totalFileSize = map["totalFileSize"]?.toLong() ?: error("Missing totalFileSize"),
                hash = map["hash"] ?: error("Missing hash")
            )
        }
        
        private fun parseSimpleJson(json: String): Map<String, String> {
            // Simplified parser for our controlled JSON format
            val trimmed = json.trim().removePrefix("{").removeSuffix("}")
            val pairs = trimmed.split(",")
            return pairs.associate { pair ->
                val (key, value) = pair.split(":")
                key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
            }
        }
    }
}
