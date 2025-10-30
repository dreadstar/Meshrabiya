package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.DistributedFileInfo
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.MeshChunk
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageOperation
import com.ustadmobile.meshrabiya.mmcp.StorageCapabilities

interface MeshNetworkInterface {
    suspend fun sendStorageRequest(targetNodeId: String, fileInfo: DistributedFileInfo, operation: StorageOperation)
    suspend fun queryFileAvailability(path: String): List<String>
    suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray?
    suspend fun getAvailableStorageNodes(): List<String>
    suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities)
    suspend fun nodeHasSpace(nodeId: String, requiredSpace: Long): Boolean
    suspend fun sendChunkToNode(nodeId: String, chunk: MeshChunk, chunkBytes: ByteArray)
    suspend fun requestChunkFromNode(nodeId: String, chunkId: String): ByteArray?
}
