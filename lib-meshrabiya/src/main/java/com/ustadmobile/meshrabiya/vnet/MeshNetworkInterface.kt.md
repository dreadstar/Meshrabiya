package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.FileTransportDTO
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.mmcp.StorageCapabilities
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionRequest
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResponse
// DEPRECATED: import com.ustadmobile.meshrabiya.service.compute.model.StorageOperation

interface MeshNetworkInterface {
    // DEPRECATED: Storage operations - November 14, 2025
    // All these methods throw NotImplementedError and were never completed
    // Use DistributedStorageManager directly via VirtualNode.getDistributedStorageManager()
    /*
    suspend fun sendStorageRequest(targetNodeId: String, fileInfo: FileTransportDTO, operation: StorageOperation)
    suspend fun queryFileAvailability(path: String): List<String>
    suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray?
    suspend fun getAvailableStorageNodes(): List<String>
    suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities)
    suspend fun nodeHasSpace(nodeId: String, requiredSpace: Long): Boolean
    suspend fun sendChunkToNode(nodeId: String, chunk: MeshChunk, chunkBytes: ByteArray)
    suspend fun requestChunkFromNode(nodeId: String, chunkId: String): ByteArray?
    */
    
    // Compute operations (Phase 2 implementation - KEEP)
    suspend fun executeRemoteTask(nodeId: String, request: TaskExecutionRequest): TaskExecutionResponse
}
