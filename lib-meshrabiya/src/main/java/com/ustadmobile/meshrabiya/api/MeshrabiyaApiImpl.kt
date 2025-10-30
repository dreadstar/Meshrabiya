package com.ustadmobile.meshrabiya.api

import java.io.File
import com.ustadmobile.meshrabiya.storage.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.service.compute.ComputeTask
import com.ustadmobile.meshrabiya.service.compute.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.JobType
import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.mesh.MeshState
import com.ustadmobile.meshrabiya.mesh.NetworkInfo
import com.ustadmobile.meshrabiya.mesh.NodeInfo
import com.ustadmobile.meshrabiya.mesh.MeshNetworkManager
import com.ustadmobile.meshrabiya.gateway.GatewayCapabilitiesManager
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

/**
 * Production-ready implementation of MeshrabiyaApi.
 * Delegates all operations to internal Meshrabiya components.
 */
class MeshrabiyaApiImpl(
    private val meshNetworkManager: MeshNetworkManager,
    private val gatewayManager: GatewayCapabilitiesManager,
    private val storageManager: DistributedStorageManager,
    private val computeService: IntelligentDistributedComputeService
) : MeshrabiyaApi {

        companion object {
        @Volatile
        private var instance: MeshrabiyaApiImpl? = null

        /**
         * Returns the singleton instance of MeshrabiyaApiImpl, initializing if necessary.
         * Uses applicationContext to avoid leaking Activity/Fragment context.
         */
        fun getInstance(context: android.content.Context): MeshrabiyaApiImpl {
            return instance ?: synchronized(this) {
                instance ?: MeshrabiyaApiImpl(
                    MeshNetworkManager.getInstance(context.applicationContext),
                    GatewayCapabilitiesManager.getInstance(context.applicationContext),
                    DistributedStorageManager.getInstance(context.applicationContext),
                    IntelligentDistributedComputeService.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }


    // --- Event Handlers ---
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
    private var onFileAddedToDropFolder: ((fileId: String, file: File) -> Unit)? = null

        // --- Mesh State & Network Info ---
    override fun getNodeRole(): Byte = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().getCurrentNodeRole()

    override fun getFitnessScore(): Int = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().getCurrentFitnessScore()

    override fun getConnectionUri(): String = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().nodeState.value.connectUri

    override fun getLocalNodeState(): com.ustadmobile.meshrabiya.vnet.LocalNodeState = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().nodeState.value

    override fun getNeighbors(): List<Int> = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().neighbors().map { it.first }

    override fun getHopCountToNode(nodeId: Int): Int? = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().getHopCountToNode(nodeId)
    // --- Mesh State & Network Info ---

        // --- Event/Callback Integration ---
    private var onMeshStateChanged: ((com.ustadmobile.meshrabiya.mesh.MeshState) -> Unit)? = null
    private var onPeerCountChanged: ((Int) -> Unit)? = null
    private var onServiceBundleReceived: ((String, ByteArray) -> Unit)? = null
    private var onServiceAnnounced: ((String, com.ustadmobile.meshrabiya.model.ServiceAnnouncement) -> Unit)? = null
    private var onGossipMessage: ((Int, ByteArray) -> Unit)? = null

    override fun setOnMeshStateChanged(handler: (newState: com.ustadmobile.meshrabiya.mesh.MeshState) -> Unit) {
        onMeshStateChanged = handler
        // TODO: Wire to meshNetworkManager or AndroidVirtualNode state flow if available
    }

    override fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit) {
        onPeerCountChanged = handler
        // TODO: Wire to meshNetworkManager peer count updates if available
    }

    override fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit) {
        onServiceBundleReceived = handler
        // TODO: Wire to AndroidVirtualNode service bundle response if available
    }

    override fun setOnServiceAnnounced(handler: (serviceId: String, announcement: com.ustadmobile.meshrabiya.model.ServiceAnnouncement) -> Unit) {
        onServiceAnnounced = handler
        // TODO: Wire to AndroidVirtualNode service announcement if available
    }

    override fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        onGossipMessage = handler
        com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().addGossipListener(handler)
    }


        // --- Service Bundle & Gateway Controls ---
    override fun announceService(serviceAnnouncement: com.ustadmobile.meshrabiya.model.ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit) {
        try {
            com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().announceService(serviceAnnouncement, signedBundle)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit) {
        try {
            val result = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().requestServiceBundle(serviceId, requesterOnionAddress)
            callback(Result.success(result))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    private var onGatewayTraffic: ((packet: com.ustadmobile.meshrabiya.vnet.VirtualPacket) -> Boolean)? = null

    override fun setOnGatewayTraffic(handler: (packet: com.ustadmobile.meshrabiya.vnet.VirtualPacket) -> Boolean) {
        onGatewayTraffic = handler
        // Optionally wire to AndroidVirtualNode if event integration is needed
    }

    override fun getMeshTrafficRouterStatus(): String {
        val router = com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode.getInstance().getMeshTrafficRouter()
        return if (router != null) "Active: ${router.javaClass.name}" else "Inactive"
    }

    init {
        // Wire internal manager event hooks to API handlers
        storageManager.onFileStored = { fileId, file -> onFileStored?.invoke(fileId, file) }
        storageManager.onFileRetrieved = { fileId, file -> onFileRetrieved?.invoke(fileId, file) }
        computeService.onTaskCompleted = { taskId, result -> onTaskCompleted?.invoke(taskId, result) }
        computeService.onTaskFailed = { taskId, error -> onOperationFailed?.invoke("taskFailed", error) }
    }

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        try {
            meshNetworkManager.startMesh()
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun stopMesh(callback: (Result<Unit>) -> Unit) {
        try {
            meshNetworkManager.stopMesh()
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getMeshStatus(): MeshState = meshNetworkManager.getMeshState()
    override fun getPeerCount(): Int = meshNetworkManager.getPeerCount()
    override fun getNetworkInfo(): NetworkInfo = meshNetworkManager.getNetworkInfo()
    override fun getNodeInfo(nodeId: String): NodeInfo = meshNetworkManager.getNodeInfo(nodeId)

    // --- Gateway Controls ---
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            gatewayManager.setTorGatewayEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getTorGatewayStatus(): Boolean = gatewayManager.isTorGatewayEnabled()
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            gatewayManager.setInternetGatewayEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getInternetGatewayStatus(): Boolean = gatewayManager.isInternetGatewayEnabled()
    override fun getGatewayStatus(): Boolean = gatewayManager.isGatewayActive()

    // --- Storage Participation ---
    override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            storageManager.setParticipationEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getStorageParticipationStatus(): Boolean = storageManager.isParticipationEnabled()
    override fun getAvailableStorageDevices(): List<StorageDevice> = storageManager.getAvailableDevices()
    override fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit) {
        try {
            storageManager.setStorageAllocation(deviceId, allocatedMB)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getStorageAllocations(): List<StorageAllocation> = storageManager.getStorageAllocations()

    override fun enableDistributedStorage() {
        distributedStorageManager.registerWithEcosystemListener(meshEcosystemListener)
        // Additional logic to start participation
    }

    override fun disableDistributedStorage() {
        distributedStorageManager.unregisterFromEcosystemListener(meshEcosystemListener)
        // Additional logic to stop participation
    }

    override fun isServiceLayerParticipating(): Boolean {
        return distributedStorageManager.participationEnabled.value
    }

    // --- Drop Folder Management ---
    override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
        try {
            storageManager.selectDropFolder(path)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getDropFolder(): File? = storageManager.getDropFolder()
    override fun getDropFolderFiles(): List<File> = storageManager.getDropFolderFiles()

    // --- File Operations ---
    override fun storeFile(file: File, callback: (Result<String>) -> Unit) {
        storageManager.storeFile(file) { result ->
            result.onSuccess { fileId ->
                callback(Result.success(fileId))
                onFileStored?.invoke(fileId, file)
            }.onFailure { error ->
                callback(Result.failure(error))
                onOperationFailed?.invoke("storeFile", error)
            }
        }
    }

    override fun retrieveFile(fileId: String, callback: (Result<File>) -> Unit) {
        storageManager.retrieveFile(fileId) { result ->
            result.onSuccess { file ->
                callback(Result.success(file))
                onFileRetrieved?.invoke(fileId, file)
            }.onFailure { error ->
                callback(Result.failure(error))
                onOperationFailed?.invoke("retrieveFile", error)
            }
        }
    }

    override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        storageManager.streamFile(fileId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("streamFile", error)
            }
        }
    }

    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        storageManager.deleteFile(fileId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("deleteFile", error)
            }
        }
    }

    override fun getAllMeshFiles(): List<MeshFile> = storageManager.getAllMeshFiles()

    // --- Distributed Service Layer ---
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            meshNetworkManager.setServiceParticipationEnabled(serviceId, enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getAvailableServices(): List<String> = meshNetworkManager.getAvailableServices()
    override fun getServiceParticipationStatus(serviceId: String): Boolean = meshNetworkManager.isServiceParticipationEnabled(serviceId)

    // --- Compute/Task Operations ---
    override fun addTask(task: ComputeTask, callback: (Result<String>) -> Unit) {
        computeService.addTask(task) { result ->
            result.onSuccess { taskId ->
                callback(Result.success(taskId))
            }.onFailure { error ->
                callback(Result.failure(error))
                onOperationFailed?.invoke("addTask", error)
            }
        }
    }

    override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        computeService.startTask(taskId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("startTask", error)
            }
        }
    }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        computeService.cancelTask(taskId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("cancelTask", error)
            }
        }
    }

    override fun getTaskStatus(taskId: String): ExecutionPlan? = computeService.getTaskStatus(taskId)
    override fun getAllTasks(): List<ComputeTask> = computeService.getAllTasks()
    override fun getJobTypes(): List<JobType> = computeService.getJobTypes()

    // --- Event Registration ---
    /**
     * Handler signature: (fileId: String, file: File) -> Unit
     */
    override fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit) {
        onFileRetrieved = handler
    }

    /**
     * Handler signature: (fileId: String, file: File) -> Unit
     */
    override fun setOnFileStored(handler: (fileId: String, file: File) -> Unit) {
        onFileStored = handler
    }

    /**
     * Handler signature: (fileId: String, success: Boolean) -> Unit
     */
    override fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit) {
        onPermissionUpdated = handler
    }

    /**
     * Handler signature: (operation: String, error: Throwable) -> Unit
     */
    override fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit) {
        onOperationFailed = handler
    }

    /**
     * Handler signature: (taskId: String, result: ExecutionPlan) -> Unit
     */
    override fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit) {
        onTaskCompleted = handler
    }

    /**
     * Handler signature: (fileId: String, recipientId: String) -> Unit
     */
    override fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit) {
        onFileShared = handler
    }

    /**
     * Handler signature: (fileId: String, file: File) -> Unit
     */
    override fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit) {
        onFileAddedToDropFolder = handler
    }

    // --- Settings and State ---
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "meshEnabled" to meshNetworkManager.isMeshEnabled(),
            "torGatewayEnabled" to gatewayManager.isTorGatewayEnabled(),
            "internetGatewayEnabled" to gatewayManager.isInternetGatewayEnabled(),
            "storageParticipationEnabled" to storageManager.isParticipationEnabled(),
            "dropFolderPath" to (storageManager.getDropFolder()?.absolutePath ?: ""),
            "availableServices" to meshNetworkManager.getAvailableServices(),
            "jobTypes" to computeService.getJobTypes()
        )
    }

    override fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit) {
        try {
            when (key) {
                "meshEnabled" -> meshNetworkManager.setMeshEnabled(value as Boolean)
                "torGatewayEnabled" -> gatewayManager.setTorGatewayEnabled(value as Boolean)
                "internetGatewayEnabled" -> gatewayManager.setInternetGatewayEnabled(value as Boolean)
                "storageParticipationEnabled" -> storageManager.setParticipationEnabled(value as Boolean)
                "dropFolderPath" -> storageManager.selectDropFolder(value as String)
                // Extend for other settings as needed
                else -> throw IllegalArgumentException("Unknown setting key: $key")
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
}