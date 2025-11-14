package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.FileTransportDTO
import com.ustadmobile.meshrabiya.mmcp.StorageCapabilities
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionRequest
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResponse
import com.ustadmobile.meshrabiya.service.compute.model.StorageOperation
import com.ustadmobile.meshrabiya.log.MNetLogger

/**
 * Bridge implementation of MeshNetworkInterface that delegates to VirtualNode's services.
 * This allows services to depend on the interface while VirtualNode instantiates everything.
 * 
 * Implementation approach:
 * - Storage operations delegate to DistributedStorageManager (once instantiated)
 * - Compute operations delegate to IntelligentDistributedComputeService
 * - NotImplementedError for operations not yet implemented
 */
internal class VirtualNode_MeshNetworkInterface(
    private val virtualNode: VirtualNode
) : MeshNetworkInterface {
    
    private val logger = MNetLogger(VirtualNode_MeshNetworkInterface::class.java)
    
    // Storage operations - delegate to DistributedStorageManager when available
    
    override suspend fun sendStorageRequest(
        targetNodeId: String, 
        fileInfo: FileTransportDTO, 
        operation: StorageOperation
    ) {
        // Will delegate to virtualNode.getDistributedStorageManager().sendStorageRequest(...)
        throw NotImplementedError("sendStorageRequest not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun queryFileAvailability(path: String): List<String> {
        // Will delegate to virtualNode.getDistributedStorageManager().queryFileAvailability(path)
        throw NotImplementedError("queryFileAvailability not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? {
        // Will delegate to virtualNode.getDistributedStorageManager().requestFileFromNode(nodeId, path)
        throw NotImplementedError("requestFileFromNode not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun getAvailableStorageNodes(): List<String> {
        // Will delegate to virtualNode.getDistributedStorageManager().getAvailableStorageNodes()
        throw NotImplementedError("getAvailableStorageNodes not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities) {
        // Will delegate to virtualNode.getDistributedStorageManager().broadcastStorageAdvertisement(capabilities)
        throw NotImplementedError("broadcastStorageAdvertisement not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun nodeHasSpace(nodeId: String, requiredSpace: Long): Boolean {
        // Will delegate to virtualNode.getDistributedStorageManager().nodeHasSpace(nodeId, requiredSpace)
        throw NotImplementedError("nodeHasSpace not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun sendChunkToNode(nodeId: String, chunk: MeshChunk, chunkBytes: ByteArray) {
        // Will delegate to virtualNode.getDistributedStorageManager().sendChunkToNode(nodeId, chunk, chunkBytes)
        throw NotImplementedError("sendChunkToNode not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    override suspend fun requestChunkFromNode(nodeId: String, chunkId: String): ByteArray? {
        // Will delegate to virtualNode.getDistributedStorageManager().requestChunkFromNode(nodeId, chunkId)
        throw NotImplementedError("requestChunkFromNode not yet implemented in VirtualNode_MeshNetworkInterface")
    }
    
    // Compute operations - delegate to IntelligentDistributedComputeService
    
    override suspend fun executeRemoteTask(
        nodeId: String, 
        request: TaskExecutionRequest
    ): TaskExecutionResponse {
        // Will delegate to virtualNode.getIntelligentDistributedComputeService().executeRemoteTask(nodeId, request)
        throw NotImplementedError("executeRemoteTask not yet implemented in VirtualNode_MeshNetworkInterface")
    }
}
