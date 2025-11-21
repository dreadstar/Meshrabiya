package com.ustadmobile.meshrabiya.api

import java.io.File
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Production-ready implementation of MeshrabiyaApi.
 * Delegates all operations to internal Meshrabiya components.
 */
class MeshrabiyaApiImpl : MeshrabiyaApi {

    // Store the application context
    @Volatile
    private var appContext: Context? = null
    override fun provideAppContext(context: Context) {
        appContext = context.applicationContext
    }

    override fun getAppContext(): Context? {
        return appContext
    }

    companion object {
        @Volatile
        private var instance: MeshrabiyaApiImpl? = null

        fun getInstance(): MeshrabiyaApiImpl {
            return instance ?: synchronized(this) {
                instance ?: MeshrabiyaApiImpl().also { instance = it }
            }
        }
    }

    // Internal managers, initialized in initMesh
    private var myNode: AndroidVirtualNode? = null
    private var distributedStorageManager: DistributedStorageManager? = null
    private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null

     // --- Proxy Controls ---
    override fun setProxy(host: String, port: Int) {
        myNode?.setProxy(host, port)
    }

    override fun setProxyActive(active: Boolean) {
        myNode?.setProxyActive(active)
    }

    // --- Mesh Initialization ---
    override fun initMesh(context: Context) {
        val Context.dataStore by preferencesDataStore(name = "meshr_settings")
        val dataStore = context.dataStore

        myNode = AndroidVirtualNode(
            appContext = context.applicationContext,
            dataStore = dataStore
        )

        distributedStorageManager = myNode?.getDistributedStorageManager()
        intelligentDistributedComputeService = myNode?.getIntelligentDistributedComputeService()
    }

    // --- Mesh State & Network Info ---
    override fun getNodeRole(): Byte = myNode?.getCurrentNodeRole() ?: 0
    override fun getFitnessScore(): Int = myNode?.getCurrentFitnessScore() ?: 0
    override fun getConnectionUri(): String = myNode?.nodeState?.value?.connectUri ?: ""
    override fun getLocalNodeState(): com.ustadmobile.meshrabiya.vnet.LocalNodeState = myNode?.nodeState?.value ?: throw IllegalStateException("Mesh not initialized")
    override fun getNeighbors(): List<Int> = myNode?.neighbors()?.map { it.first } ?: emptyList()
    override fun getHopCountToNode(nodeId: Int): Int? = myNode?.getHopCountToNode(nodeId)

    override fun getConnectLink(): String? = myNode.state.filter {
        it.connectUri != null
    }.first()
    override fun getConnectLinkFlow(): Flow<String?> = myNode?.state?.map { it.connectUri } ?: flowOf(null)

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        try {
            runBlocking {
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.DEFAULT
                )
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun stopMesh(callback: (Result<Unit>) -> Unit) {
        try {
            runBlocking {
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.DEFAULT
                )
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getMeshStatus(): MeshState = myNode?.getMeshStatus() ?: MeshState.UNKNOWN
    override fun getPeerCount(): Int = myNode?.getPeerCount() ?: 0
    override fun getNetworkInfo(): NetworkInfo = myNode?.getNetworkInfo() ?: NetworkInfo()
    override fun getNodeInfo(nodeId: String): NodeInfo = myNode?.getNodeInfo(nodeId) ?: NodeInfo()

    // --- Gateway Controls ---
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            myNode?.setTorGatewayEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getTorGatewayStatus(): Boolean = myNode?.getTorGatewayStatus() ?: false
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            myNode?.setInternetGatewayEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getInternetGatewayStatus(): Boolean = myNode?.getInternetGatewayStatus() ?: false
    override fun getGatewayStatus(): Boolean = myNode?.getGatewayStatus() ?: false

    // --- Storage Participation ---
    override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            distributedStorageManager?.setParticipationEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getStorageParticipationStatus(): Boolean = distributedStorageManager?.isParticipationEnabled() ?: false
    override fun getAvailableStorageDevices(): List<StorageDevice> = distributedStorageManager?.getAvailableDevices() ?: emptyList()
    override fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit) {
        try {
            distributedStorageManager?.setStorageAllocation(deviceId, allocatedMB)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getStorageAllocations(): List<StorageAllocation> = distributedStorageManager?.getStorageAllocations() ?: emptyList()
    override fun enableDistributedStorage() {
        distributedStorageManager?.registerWithEcosystemListener(myNode?.getMeshEcosystemListener())
    }
    override fun disableDistributedStorage() {
        distributedStorageManager?.unregisterFromEcosystemListener(myNode?.getMeshEcosystemListener())
    }
    override fun isServiceLayerParticipating(): Boolean {
        return distributedStorageManager?.participationEnabled?.value ?: false
    }

    // --- Drop Folder Management ---
    override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
        try {
            distributedStorageManager?.selectDropFolder(path)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getDropFolder(): File? = distributedStorageManager?.getDropFolder()
    override fun getDropFolderFiles(): List<File> = distributedStorageManager?.getDropFolderFiles() ?: emptyList()

    // --- File Operations ---
    override fun storeFile(file: File, callback: (Result<String>) -> Unit) {
        distributedStorageManager?.storeFile(file) { result ->
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
        distributedStorageManager?.retrieveFile(fileId) { result ->
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
        distributedStorageManager?.streamFile(fileId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("streamFile", error)
            }
        }
    }
    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        distributedStorageManager?.deleteFile(fileId) { result ->
            callback(result)
            result.onFailure { error ->
                onOperationFailed?.invoke("deleteFile", error)
            }
        }
    }
    override fun getAllMeshFiles(): List<MeshFile> = distributedStorageManager?.getAllMeshFiles() ?: emptyList()

    // --- Distributed Service Layer ---
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            myNode?.setServiceParticipationEnabled(serviceId, enabled)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getAvailableServices(): List<String> = myNode?.getAvailableServices() ?: emptyList()
    override fun getServiceParticipationStatus(serviceId: String): Boolean {
        return myNode?.getServiceParticipationStatus(serviceId) ?: false
    }

    // --- Compute/Task Operations ---
    override fun addTask(requestParams: Map<String, Any>): ApiResult {
        val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
        val serviceId = requestParams["serviceId"] as? String ?: "unknown_service"
        val inputParams = requestParams["inputParams"] as? Map<String, Any> ?: emptyMap()
        val metadata = requestParams

        // Create canonical MeshEcosystemMessage for compute task request
        val computeTaskMsg = ComputeTaskRequestMessage(
            taskId = taskId,
            serviceId = serviceId,
            inputParams = inputParams,
            metadata = metadata
        )

        // Pass the ecosystem message to the compute service for processing and broadcast
        intelligentDistributedComputeService?.processTaskRequest(computeTaskMsg)

        return ApiResult.Success // Optionally return taskId or status
    }

    override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        intelligentDistributedComputeService?.startTask(taskId, callback)
    }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        intelligentDistributedComputeService?.cancelTask(taskId, callback)
    }
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These implementations reference scheduler types that are unused in Phase 3-4
    // override fun getTaskStatus(taskId: String): ExecutionPlan? = intelligentDistributedComputeService?.getTaskStatus(taskId)
    // override fun getAllTasks(): List<ComputeTask> = intelligentDistributedComputeService?.getAllTasks() ?: emptyList()
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
    private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileAddedToDropFolder: ((fileId: String, file: File) -> Unit)? = null

    override fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit) {
        onFileRetrieved = handler
    }
    override fun setOnFileStored(handler: (fileId: String, file: File) -> Unit) {
        onFileStored = handler
    }
    override fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit) {
        onPermissionUpdated = handler
    }
    override fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit) {
        onOperationFailed = handler
    }
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // override fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit) {
    //     onTaskCompleted = handler
    // }
    
    
    override fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit) {
        onFileShared = handler
    }
    override fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit) {
        onFileAddedToDropFolder = handler
    }

    // --- Settings and State ---
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "dropFolderPath" to (distributedStorageManager?.getDropFolder()?.absolutePath ?: ""),
            "availableServices" to (myNode?.getAvailableServices() ?: emptyList<String>()),
            "jobTypes" to (intelligentDistributedComputeService?.getJobTypes() ?: emptyList<JobType>())
        )
    }
    override fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit) {
        try {
            when (key) {
                "dropFolderPath" -> distributedStorageManager?.selectDropFolder(value as String)
                else -> throw IllegalArgumentException("Unknown setting key: $key")
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    // --- Service Bundle & Gateway Controls ---
    // override fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit) {
    //     try {
    //         myNode?.announceService(serviceAnnouncement, signedBundle)
    //         callback(Result.success(Unit))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    // override fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit) {
    //     try {
    //         val result = myNode?.requestServiceBundle(serviceId, requesterOnionAddress)
    //         callback(Result.success(result))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    private var onGatewayTraffic: ((packet: VirtualPacket) -> Boolean)? = null
    override fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean) {
        onGatewayTraffic = handler
    }
    override fun getMeshTrafficRouterStatus(): String {
        val router = myNode?.getMeshTrafficRouter()
        return if (router != null) "Active: ${router.javaClass.name}" else "Inactive"
    }

    // --- Event/Callback Integration ---
    private var onMeshStateChanged: ((MeshState) -> Unit)? = null
    private var onPeerCountChanged: ((Int) -> Unit)? = null
    // private var onServiceBundleReceived: ((String, ByteArray) -> Unit)? = null
    // private var onServiceAnnounced: ((String, ServiceAnnouncement) -> Unit)? = null
    private var onGossipMessage: ((Int, ByteArray) -> Unit)? = null

    override fun setOnMeshStateChanged(handler: (newState: MeshState) -> Unit) {
        onMeshStateChanged = handler
    }
    override fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit) {
        onPeerCountChanged = handler
    }
    // override fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit) {
    //     onServiceBundleReceived = handler
    // }
    // override fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit) {
    //     onServiceAnnounced = handler
    // }
    override fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        onGossipMessage = handler
        myNode?.addGossipListener(handler)
    }
}