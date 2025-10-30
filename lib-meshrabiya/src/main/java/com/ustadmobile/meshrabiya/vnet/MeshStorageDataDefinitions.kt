package com.ustadmobile.meshrabiya.vnet

/**
 * Represents a chunk of a file stored in the mesh network.
 * Stores recipient key IDs and session keys for per-chunk access control.
 */
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long = System.currentTimeMillis()
    // Permission-related fields
    val recipientKeyIds: List<Long> = emptyList(), // PGPPublicKey.keyID values
    val sessionKeys: Map<Long, ByteArray> = emptyMap() // keyID -> encrypted session key
)

data class MeshFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val storedAt: Long = System.currentTimeMillis()
)


