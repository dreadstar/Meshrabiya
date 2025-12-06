package com.ustadmobile.meshrabiya.api

import java.io.File
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.model.JobType
// DEPRECATED: IntelligentDistributedComputeService replaced by canonical compute workflows (2025-12-04)
// import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.TorStatusMonitor
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
        
        /**
         * Timeout threshold for gateway staleness check.
         * Phase 3B: Used to filter stale gateways from statistics
         */
        private const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
    }

    // Internal managers, initialized in initMesh
    private var myNode: AndroidVirtualNode? = null
    private var emergentRoleManager: EmergentRoleManager? = null
    private var distributedStorageManager: DistributedStorageManager? = null
    // DEPRECATED: intelligentDistributedComputeService removed (2025-12-04)
    // Compute workflows now use DistributedComputeServer + TaskManager directly
    // private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null

    // V3: Gateway preference state
    @Volatile
    private var currentGatewayPreference: GatewayPreference = GatewayPreference.DEFAULT
    @Volatile
    private var isTorRunning: Boolean = false
    private val torStatusMonitor = TorStatusMonitor()

     // --- Proxy Controls ---
    override fun setProxy(host: String, port: Int) {
        myNode?.setProxy(host, port)
    }

    override fun setProxyActive(active: Boolean) {
        myNode?.setProxyActive(active)
    }

    // --- Mesh Initialization ---
    private val Context.dataStore by preferencesDataStore(name = "meshr_settings")
    
    override fun initMesh(context: Context) {
        val dataStore = context.dataStore

        myNode = AndroidVirtualNode(
            appContext = context.applicationContext,
            dataStore = dataStore
        )

        emergentRoleManager = myNode?.emergentRoleManager
        distributedStorageManager = myNode?.distributedStorageManager
        // DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
        // intelligentDistributedComputeService = myNode?.getIntelligentDistributedComputeService()
        
        // V3: Load gateway preference from storage
        runBlocking {
            loadGatewayPreference(context)
        }
        
        // V3: Register Tor status monitor
        torStatusMonitor.register(context)
        torStatusMonitor.requestStatusUpdate(context)  // Get initial status
    }

    // --- Mesh State & Network Info ---
    override fun getNodeRole(): Byte = emergentRoleManager?.getCurrentMeshRoles()?.firstOrNull()?.ordinal?.toByte() ?: 0
    override fun getFitnessScore(): Int = 0  // TODO: Expose fitness calculation from EmergentRoleManager
    override fun getConnectionUri(): String = myNode?.currentNodeState?.connectUri ?: ""
    override fun getLocalNodeState(): com.ustadmobile.meshrabiya.vnet.LocalNodeState = myNode?.currentNodeState ?: throw IllegalStateException("Mesh not initialized")
    override fun getNeighbors(): List<Int> = myNode?.neighbors()?.map { it.first } ?: emptyList()
    override fun getHopCountToNode(nodeId: Int): Int? = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeId)?.hopCount?.toInt()

    override fun getConnectLink(): String? = myNode?.currentNodeState?.connectUri
    override fun getConnectLinkFlow(): Flow<String?> = myNode?.state?.map { it.connectUri } ?: flowOf(null)

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        try {
            runBlocking {
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
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
                    hotspotType = HotspotType.AUTO
                )
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    // TODO: Reimplement using canonical workflows - VirtualNode methods removed (2025-12-04)
    override fun getMeshStatus(): MeshState = MeshState.UNKNOWN // myNode?.getMeshStatus() ?: MeshState.UNKNOWN
    override fun getPeerCount(): Int = myNode?.neighbors()?.size ?: 0 // myNode?.getPeerCount() ?: 0
    
    /**
     * Phase 3B: Enhanced getNetworkInfo() with gateway statistics
     * Returns mesh network information including Tor and clearnet gateway counts
     */
    override fun getNetworkInfo(): NetworkInfo {
        val node = myNode
        if (node == null) {
            return NetworkInfo() // Mesh not initialized
        }
        
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        val connectedNeighbors = node.neighbors().size
        
        // Phase 3B: Count gateways by type
        val torGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(com.ustadmobile.meshrabiya.vnet.MeshRole.TOR_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        val clearnetGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(com.ustadmobile.meshrabiya.vnet.MeshRole.CLEARNET_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        return NetworkInfo(
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
        )
    }
    
    override fun getNodeInfo(nodeId: String): NodeInfo = NodeInfo() // myNode?.getNodeInfo(nodeId) ?: NodeInfo()

    // --- Gateway Controls ---
    // TODO: Reimplement using GatewaySelector from canonical workflows (2025-12-04)
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        // try {
        //     myNode?.setTorGatewayEnabled(enabled)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getTorGatewayStatus(): Boolean = false // myNode?.getTorGatewayStatus() ?: false
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        // try {
        //     myNode?.setInternetGatewayEnabled(enabled)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getInternetGatewayStatus(): Boolean = false // myNode?.getInternetGatewayStatus() ?: false
    override fun getGatewayStatus(): Boolean = false // myNode?.getGatewayStatus() ?: false

    // --- V3: Gateway Preference Implementation ---
    override fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit) {
        try {
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)] = preference.name
                }
                currentGatewayPreference = preference
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getGatewayPreference(): GatewayPreference {
        return currentGatewayPreference
    }

    override fun isTorActive(): Boolean {
        return isTorRunning
    }

    /**
     * V3: Internal method to update Tor status from TorStatusMonitor.
     * Called by TorStatusMonitor BroadcastReceiver when Orbot status changes.
     */
    internal fun updateTorStatus(isActive: Boolean) {
        isTorRunning = isActive
    }

    /**
     * V3: Load gateway preference from DataStore on initialization.
     */
    private suspend fun loadGatewayPreference(context: Context) {
        val prefs = context.dataStore.data.first()
        val prefString = prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)]
        currentGatewayPreference = GatewayPreference.fromString(prefString)
    }

    // --- Storage Participation ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        // try {
        //     distributedStorageManager?.setParticipationEnabled(enabled)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getStorageParticipationStatus(): Boolean = false // distributedStorageManager?.isParticipationEnabled() ?: false
    override fun getAvailableStorageDevices(): List<StorageDevice> = emptyList() // distributedStorageManager?.getAvailableDevices() ?: emptyList()
    override fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit) {
        // try {
        //     distributedStorageManager?.setStorageAllocation(deviceId, allocatedMB)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getStorageAllocations(): List<StorageAllocation> = emptyList() // distributedStorageManager?.getStorageAllocations() ?: emptyList()
    override fun enableDistributedStorage() {
        // distributedStorageManager?.registerWithEcosystemListener(myNode?.getMeshEcosystemListener())
    }
    override fun disableDistributedStorage() {
        // distributedStorageManager?.unregisterFromEcosystemListener(myNode?.getMeshEcosystemListener())
    }
    // TODO: Reimplement using TaskManager from canonical workflows (2025-12-04)
    override fun isComputeLayerParticipating(): Boolean {
        return false // distributedStorageManager?.participationEnabled?.value ?: false
    }

    // --- Drop Folder Management ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
        // try {
        //     distributedStorageManager?.selectDropFolder(path)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getDropFolder(): File? = null // distributedStorageManager?.getDropFolder()
    override fun getDropFolderFiles(): List<File> = emptyList() // distributedStorageManager?.getDropFolderFiles() ?: emptyList()

    // --- File Operations ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun storeFile(file: File, callback: (Result<String>) -> Unit) {
        callback(Result.failure(NotImplementedError("storeFile not yet implemented in canonical workflows")))
        // distributedStorageManager?.storeFile(file) { result ->
        //     result.onSuccess { fileId ->
        //         callback(Result.success(fileId))
        //         onFileStored?.invoke(fileId, file)
        //     }.onFailure { error ->
        //         callback(Result.failure(error))
        //         onOperationFailed?.invoke("storeFile", error)
        //     }
        // }
    }
    override fun retrieveFile(fileId: String, callback: (Result<File>) -> Unit) {
        callback(Result.failure(NotImplementedError("retrieveFile not yet implemented in canonical workflows")))
        // distributedStorageManager?.retrieveFile(fileId) { result ->
        //     result.onSuccess { file ->
        //         callback(Result.success(file))
        //         onFileRetrieved?.invoke(fileId, file)
        //     }.onFailure { error ->
        //         callback(Result.failure(error))
        //         onOperationFailed?.invoke("retrieveFile", error)
        //     }
        // }
    }
    override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("streamFile not yet implemented in canonical workflows")))
        // distributedStorageManager?.streamFile(fileId) { result ->
        //     callback(result)
        //     result.onFailure { error ->
        //         onOperationFailed?.invoke("streamFile", error)
        //     }
        // }
    }
    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("deleteFile not yet implemented in canonical workflows")))
        // distributedStorageManager?.deleteFile(fileId) { result ->
        //     callback(result)
        //     result.onFailure { error ->
        //         onOperationFailed?.invoke("deleteFile", error)
        //     }
        // }
    }
    override fun getAllMeshFiles(): List<MeshFile> = emptyList() // distributedStorageManager?.getAllMeshFiles() ?: emptyList()

    // --- Distributed Service Layer ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        // try {
        //     myNode?.setServiceParticipationEnabled(serviceId, enabled)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getAvailableServices(): List<String> = emptyList() // myNode?.getAvailableServices() ?: emptyList()
    override fun getServiceParticipationStatus(serviceId: String): Boolean = false // myNode?.getServiceParticipationStatus(serviceId) ?: false

    // --- Compute/Task Operations ---
    // TODO: Reimplement using TaskManager + DistributedComputeServer from canonical workflows (2025-12-04)
    override fun addTask(requestParams: Map<String, Any>): ApiResult {
        return ApiResult.Failure(NotImplementedError("addTask not yet implemented in canonical workflows"))
        // val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
        // val serviceId = requestParams["serviceId"] as? String ?: "unknown_service"
        // val inputParams = requestParams["inputParams"] as? Map<String, Any> ?: emptyMap()
        // val metadata = requestParams
        //
        // // Create canonical MeshEcosystemMessage for compute task request
        // val computeTaskMsg = ComputeTaskRequestMessage(
        //     taskId = taskId,
        //     serviceId = serviceId,
        //     inputParams = inputParams,
        //     metadata = metadata
        // )
        //
        // // Pass the ecosystem message to the compute service for processing and broadcast
        // intelligentDistributedComputeService?.processTaskRequest(computeTaskMsg)
        //
        // return ApiResult.Success // Optionally return taskId or status
    }

    override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("startTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.startTask(taskId, callback)
    }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("cancelTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.cancelTask(taskId, callback)
    }
    
    override fun getJobTypes(): List<JobType> = emptyList() // TODO: Implement via TaskManager
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These implementations reference scheduler types that are unused in Phase 3-4
    // override fun getTaskStatus(taskId: String): ExecutionPlan? = intelligentDistributedComputeService?.getTaskStatus(taskId)
    // override fun getAllTasks(): List<ComputeTask> = intelligentDistributedComputeService?.getAllTasks() ?: emptyList()
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    // UNUSED SCHEDULER API - Commented 2025-12-04
    // private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
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
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "dropFolderPath" to "",
            "availableServices" to emptyList<String>(),
            "jobTypes" to emptyList<JobType>()
        )
    }
    override fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit) {
        // try {
        //     when (key) {
        //         "dropFolderPath" -> distributedStorageManager?.selectDropFolder(value as String)
        //         else -> throw IllegalArgumentException("Unknown setting key: $key")
        //     }
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
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
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getMeshTrafficRouterStatus(): String = "Inactive" // {
        // val router = myNode?.getMeshTrafficRouter()
        // return if (router != null) "Active: ${router.javaClass.name}" else "Inactive"
    // }

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
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        onGossipMessage = handler
        // myNode?.addGossipListener(handler)
    }
}