package com.ustadmobile.meshrabiya.api.model

import java.util.UUID

/**
 * Result of broadcast send operation
 */
data class BroadcastResultDto(
    val broadcastId: String,
    val messageText: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
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
    val latitude: Double? = null,
    val longitude: Double? = null,
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
