package com.ustadmobile.meshrabiya.api

import java.io.File
import com.ustadmobile.meshrabiya.storage.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.service.compute.ComputeTask
import com.ustadmobile.meshrabiya.service.compute.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.JobType
import com.ustadmobile.meshrabiya.mesh.MeshState
import com.ustadmobile.meshrabiya.mesh.NetworkInfo
import com.ustadmobile.meshrabiya.mesh.NodeInfo

/**
 * Unified API interface for Meshrabiya module.
 * Exposes all key operations, state, and event registration for UI/control layers.
 */
interface MeshrabiyaApi {

    // --- Mesh State & Network Info ---
    fun getNodeRole(): Byte
    fun getFitnessScore(): Int
    fun getConnectionUri(): String
    fun getLocalNodeState(): com.ustadmobile.meshrabiya.vnet.LocalNodeState
    fun getNeighbors(): List<Int>
    fun getHopCountToNode(nodeId: Int): Int?

    // --- Mesh Network Controls ---
    fun startMesh(callback: (Result<Unit>) -> Unit)
    fun stopMesh(callback: (Result<Unit>) -> Unit)
    fun getMeshStatus(): MeshState
    fun getPeerCount(): Int
    fun getNetworkInfo(): NetworkInfo
    fun getNodeInfo(nodeId: String): NodeInfo

    // --- Gateway Controls ---
    fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getTorGatewayStatus(): Boolean
    fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getInternetGatewayStatus(): Boolean
    fun getGatewayStatus(): Boolean

    // --- Storage Participation ---
    fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getStorageParticipationStatus(): Boolean
    fun getAvailableStorageDevices(): List<StorageDevice>
    fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit)
    fun getStorageAllocations(): List<StorageAllocation>
    fun enableDistributedStorage()
    fun disableDistributedStorage()
    fun isServiceLayerParticipating(): Boolean

    // --- Drop Folder Management ---
    fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
    fun getDropFolder(): File?
    fun getDropFolderFiles(): List<File>

    // --- File Operations ---
    fun storeFile(file: File, callback: (Result<String>) -> Unit)
    fun retrieveFile(fileId: String, callback: (Result<File>) -> Unit)
    fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun getAllMeshFiles(): List<MeshFile>

    // --- Distributed Service Layer ---
    fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getAvailableServices(): List<String>
    fun getServiceParticipationStatus(serviceId: String): Boolean

    // --- Compute/Task Operations ---
    fun addTask(requestParams: Map<String, Any>): ApiResult {
        val localRequest = LocalComputeTaskRequest(
            mmcpRequest = MmcpComputeTaskRequest.fromParams(requestParams),
            metadata = requestParams
        )
        IntelligentDistributedComputeService.processTaskRequest(localRequest)
        // Add to client-side task requests list
        ClientTaskRequestTracker.add(localRequest)
        // Return API result, possibly with taskId or status
    }
    fun startTask(taskId: String, callback: (Result<Unit>) -> Unit)
    fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
    fun getTaskStatus(taskId: String): ExecutionPlan?
    fun getAllTasks(): List<ComputeTask>
    fun getJobTypes(): List<JobType>

    // --- Event Registration ---
    fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)
    fun setOnFileStored(handler: (fileId: String, file: File) -> Unit)
    fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)
    fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)
    fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit)
    fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
    fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)

    // --- Settings and State ---
    fun getSettings(): Map<String, Any>
    fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)

    // --- Service Bundle & Gateway Controls ---
    fun announceService(serviceAnnouncement: com.ustadmobile.meshrabiya.model.ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit)

    fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit)

    fun setOnGatewayTraffic(handler: (packet: com.ustadmobile.meshrabiya.vnet.VirtualPacket) -> Boolean)

    fun getMeshTrafficRouterStatus(): String

        // --- Event/Callback Integration ---
    fun setOnMeshStateChanged(handler: (newState: com.ustadmobile.meshrabiya.mesh.MeshState) -> Unit)
    fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit)
    fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit)
    fun setOnServiceAnnounced(handler: (serviceId: String, announcement: com.ustadmobile.meshrabiya.model.ServiceAnnouncement) -> Unit)
    fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)

}