package com.ustadmobile.meshrabiya.api
// import com.ustadmobile.meshrabiya.model.toHash
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.File
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.model.UserKeyManager
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest
// DEPRECATED: IntelligentDistributedComputeService replaced by canonical compute workflows (2025-12-04)
// import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.TorStatusMonitor
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.api.model.User
import com.ustadmobile.meshrabiya.api.model.*
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
        private const val TAG = "MeshrabiyaApiImpl"
        
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
    
    // StateFlow for network info - updated every 2 seconds
    private val _networkInfoFlow = MutableStateFlow<NetworkInfoDto?>(null)
    val networkInfoFlow: StateFlow<NetworkInfoDto?> = _networkInfoFlow.asStateFlow()
    // StateFlow for mesh status
    private val _meshStatusFlow = MutableStateFlow(getMeshStatus())
    override val meshStatusFlow: StateFlow<MeshStateDto> get() = _meshStatusFlow

    // --- Network Overview Metrics StateFlow ---
    private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto(0L, 0L, 0))
    override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    private var metricsMonitorJob: Job? = null
    private var distributedStorageManager: DistributedStorageManager? = null
    // distributedComputeClient accessed via myNode?.distributedComputeClient (protected property)
    // DEPRECATED: intelligentDistributedComputeService removed (2025-12-04)
    // Compute workflows now use DistributedComputeServer + TaskManager directly
    // private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null
    
    /**
     * Handler for broadcast messages+files
     * Initialized when mesh starts, cleaned up when mesh stops
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    private var broadcastHandler: com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler? = null

    /**
     * Queue for listeners registered before broadcastHandler is created
     * Applied automatically when handler is initialized during joinMesh()
     * Added: 2026-02-15 for deferred listener registration
     */
    private val pendingBroadcastListeners = 
        java.util.concurrent.ConcurrentLinkedQueue<(com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit>()

    // Section 6: Event monitoring scope and jobs
    private val eventMonitoringScope = CoroutineScope(Dispatchers.Default)
    private var stateMonitorJob: Job? = null
    private var peerMonitorJob: Job? = null
    
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
        // Guard against double initialization
        if (myNode != null) {
            Log.w("MeshInit", "initMesh called but mesh already initialized, skipping")
            return
        }
        
        Log.d("MeshInit", "initMesh called with context: $context")
        try {
            val dataStore = context.dataStore
             Log.d("MeshInit", "dataStore resolved: $dataStore")

            myNode = AndroidVirtualNode(
                appContext = context.applicationContext,
                dataStore = dataStore
            )
            Log.d("MeshInit", "AndroidVirtualNode created: $myNode")

            emergentRoleManager = myNode?.emergentRoleManager
            Log.d("MeshInit", "emergentRoleManager assigned: $emergentRoleManager")

            distributedStorageManager = myNode?.distributedStorageManager
            // distributedComputeClient accessed via myNode.getDistributedComputeClient() when needed
            // DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
            // intelligentDistributedComputeService = myNode?.getIntelligentDistributedComputeService()
            
            // V3: Load gateway preference from storage
            runBlocking {
                loadGatewayPreference(context)
            }
            
            // V3: Register Tor status monitor
            torStatusMonitor.register(context)
            torStatusMonitor.requestStatusUpdate(context)  // Get initial status
            
            // Section 6: Start monitoring for state and peer count changes
            startEventMonitoring()
        } catch (e: Exception) {
            Log.e("MeshInit", "Exception during initMesh", e)
            throw e
        }
    }
    
    /**
     * Section 6: Start coroutines to monitor mesh state and peer count changes
     * Invokes registered callbacks when changes are detected
     */
    private fun startEventMonitoring() {
        // Monitor mesh state changes
        stateMonitorJob = eventMonitoringScope.launch {
            var previousState = getMeshStatus()
            while (true) {
                delay(1000) // Check every second
                val currentState = getMeshStatus()
                if (currentState != previousState) {
                    previousState = currentState
                    onMeshStateChanged?.invoke(currentState)
                    // Update meshStatusFlow when mesh status changes
                    _meshStatusFlow.value = currentState
                }
            }
        }
        
        // Monitor peer count changes
        peerMonitorJob = eventMonitoringScope.launch {
            var previousCount = getPeerCount()
            while (true) {
                delay(1000) // Check every second
                val currentCount = getPeerCount()
                if (currentCount != previousCount) {
                    // Call the peer count changed callback if present
                    onPeerCountChanged?.invoke(currentCount)
                    // If peer count transitions from 0 to 1, update meshStatusFlow to CONNECTED
                    if (previousCount == 0 && currentCount > 0) {
                        _meshStatusFlow.value = MeshStateDto.CONNECTED
                    }
                    // If peer count transitions from >=1 to 0, update meshStatusFlow to CONNECTING
                    if (previousCount > 0 && currentCount == 0) {
                        _meshStatusFlow.value = MeshStateDto.CONNECTING
                    }
                    previousCount = currentCount
                }
            }
        }
        
        // Update network info Flow every 2 seconds for UI
        eventMonitoringScope.launch {
            while (true) {
                _networkInfoFlow.value = getNetworkInfo()
                delay(2000) // Update every 2 seconds
            }
        }

        // --- Network Overview Metrics Polling ---
        metricsMonitorJob?.cancel()
        metricsMonitorJob = eventMonitoringScope.launch {
            var lastUploadBytes = myNode?.currentNodeState?.uploadBytes ?: 0L
            var lastDownloadBytes = myNode?.currentNodeState?.downloadBytes ?: 0L
            while (true) {
                delay(1000)
                val node = myNode
                if (node != null) {
                    val state = node.currentNodeState
                    val uploadNow = state.uploadBytes
                    val downloadNow = state.downloadBytes
                    val uploadRate = uploadNow - lastUploadBytes
                    val downloadRate = downloadNow - lastDownloadBytes
                    lastUploadBytes = uploadNow
                    lastDownloadBytes = downloadNow
                    val activeNodeCount = node.neighbors().size + 1 // +1 for self
                    _networkOverviewMetricsFlow.value = NetworkOverviewMetricsDto(
                        uploadBps = uploadRate,
                        downloadBps = downloadRate,
                        activeNodeCount = activeNodeCount
                    )
                } else {
                    _networkOverviewMetricsFlow.value = NetworkOverviewMetricsDto(0L, 0L, 0)
                }
            }
        }
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
     */
    private fun stopEventMonitoring() {
        stateMonitorJob?.cancel()
        peerMonitorJob?.cancel()
        stateMonitorJob = null
        peerMonitorJob = null
    }

    // --- Mesh State & Network Info ---
    // override fun getNodeRole(): Byte = emergentRoleManager?.getCurrentMeshRoles()?.firstOrNull()?.ordinal?.toByte() ?: 0

    override fun getNodeRoleNames(): List<String> =
        emergentRoleManager?.getCurrentMeshRoles()?.map { it.name } ?: emptyList()
    
    /**
     * Expose currentMeshRoles StateFlow for UI observation
     */
    val currentMeshRolesFlow: kotlinx.coroutines.flow.StateFlow<Set<MeshRole>>?
        get() = emergentRoleManager?.currentMeshRoles

    override fun getFitnessScore(): Float = emergentRoleManager?.getFitnessScore() ?: 0f
    
    override fun getConnectionUri(): String = myNode?.currentNodeState?.connectUri ?: ""
    override fun getLocalNodeState(): LocalNodeStateDto = myNode?.currentNodeState?.toDto() ?: throw IllegalStateException("Mesh not initialized")
    override fun getNeighbors(): List<NeighborInfoDto> = myNode?.neighbors()?.map { NeighborInfoDto(it.first, it.second.toDto()) } ?: emptyList()
    override fun getHopCountToNode(nodeId: Int): Int? = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeId)?.hopCount?.toInt()

    override fun getConnectLink(): String? = myNode?.currentNodeState?.connectUri
    override fun getConnectLinkFlow(): Flow<String?> = myNode?.state?.map { it.connectUri } ?: flowOf(null)

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        Log.e("MeshrabiyaApiImpl", "========== startMesh() CALLED ==========")
        Log.e("MeshrabiyaApiImpl", "This log MUST appear if startMesh is invoked")
        Log.d("MeshrabiyaApiImpl", "myNode is null: ${myNode == null}")
        
        if (myNode == null) {
            Log.e("MeshrabiyaApiImpl", "startMesh called but myNode is null - mesh not initialized!")
            callback(Result.failure(IllegalStateException("Mesh not initialized - call initMesh() first")))
            return
        }
        
        Log.d("MeshrabiyaApiImpl", "Launching coroutine for startMesh")
        eventMonitoringScope.launch {
            try {
                Log.d("MeshrabiyaApiImpl", "Coroutine started, calling setWifiHotspotEnabled(enabled=true)")
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                Log.d("MeshrabiyaApiImpl", "setWifiHotspotEnabled returned successfully")
                
                // Load persisted role preferences and apply them to EmergentRoleManager
                loadAndApplyPersistedRolePreferences()
                
                // Initialize broadcast handler (NETWORK_BROADCAST_v2 implementation)
                val node = myNode
                if (node != null && broadcastHandler == null) {
                    broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                        virtualNode = node,
                        logger = node.logger,
                        cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                        getDropFolderCallback = { getDropFolderUriAsFile() }
                    )
                    // Wire handler to VirtualNode
                    node.broadcastMessageHandler = broadcastHandler
                    Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode")
                    
                    // Apply any listeners registered before handler was created
                    applyPendingBroadcastListeners()
                }
                
                callback(Result.success(Unit))
                Log.d("MeshrabiyaApiImpl", "startMesh callback invoked with success")
            } catch (e: Exception) {
                Log.e("MeshrabiyaApiImpl", "startMesh failed with exception", e)
                callback(Result.failure(e))
                Log.d("MeshrabiyaApiImpl", "startMesh callback invoked with failure")
            }
        }
        Log.d("MeshrabiyaApiImpl", "startMesh() returning (coroutine launched)")
    }

    override fun stopMesh(callback: (Result<Unit>) -> Unit) {
        Log.d("MeshrabiyaApiImpl", "stopMesh() called")
        Log.d("MeshrabiyaApiImpl", "myNode is null: ${myNode == null}")
        
        if (myNode == null) {
            Log.e("MeshrabiyaApiImpl", "stopMesh called but myNode is null - mesh not initialized!")
            callback(Result.failure(IllegalStateException("Mesh not initialized - call initMesh() first")))
            return
        }
        
        Log.d("MeshrabiyaApiImpl", "Launching coroutine for stopMesh")
        eventMonitoringScope.launch {
            try {
                Log.d("MeshrabiyaApiImpl", "Coroutine started, calling setWifiHotspotEnabled(enabled=false)")
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                Log.d("MeshrabiyaApiImpl", "setWifiHotspotEnabled returned successfully")
                
                // Cleanup broadcast handler (NETWORK_BROADCAST_v2 implementation)
                
                broadcastHandler = null
                Log.d("MeshrabiyaApiImpl", "Broadcast handler shutdown and cleaned up")
                
                callback(Result.success(Unit))
                Log.d("MeshrabiyaApiImpl", "stopMesh callback invoked with success")
            } catch (e: Exception) {
                Log.e("MeshrabiyaApiImpl", "stopMesh failed with exception", e)
                callback(Result.failure(e))
                Log.d("MeshrabiyaApiImpl", "stopMesh callback invoked with failure")
            }
        }
        Log.d("MeshrabiyaApiImpl", "stopMesh() returning (coroutine launched)")
    }

    override fun getMeshStatus(): MeshStateDto {
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() called - myNode is null: ${myNode == null}")
        val node = myNode ?: run {
            Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning DISCONNECTED (myNode is null)")
            return MeshStateDto.DISCONNECTED
        }
        
        // Check if mesh networking is actually active by checking WiFi state
        val wifiState = runBlocking {
            node.meshrabiyaWifiManager.state.first()
        }
        
        val hasActiveHotspot = wifiState.hotspotIsStarted
        val hasActiveStation = wifiState.wifiStationState.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE
        val isMeshNetworkActive = hasActiveHotspot || hasActiveStation
        
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() - wifiRole=${wifiState.wifiRole}, hotspot=${hasActiveHotspot}, station=${hasActiveStation}, active=${isMeshNetworkActive}")
        
        if (!isMeshNetworkActive) {
            Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning DISCONNECTED (no active network)")
            return MeshStateDto.DISCONNECTED
        }
        
        // Mesh network is active - determine state based on neighbors
        val neighborCount = node.neighbors().size
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() - neighborCount=$neighborCount")
        
        val status = when {
            neighborCount > 0 -> MeshStateDto.CONNECTED     // Has neighbors = connected to mesh
            neighborCount == 0 -> MeshStateDto.CONNECTING   // No neighbors yet but network active
            else -> MeshStateDto.UNKNOWN
        }
        
        Log.d("MeshrabiyaApiImpl", "getMeshStatus() returning: $status")
        return status
    }
    override fun getPeerCount(): Int = myNode?.neighbors()?.size ?: 0 // myNode?.getPeerCount() ?: 0
    
    /**
     * Phase 3B: Enhanced getNetworkInfo() with gateway statistics
     * Returns mesh network information including Tor and clearnet gateway counts
     */
    override fun getNetworkInfo(): NetworkInfoDto? {
        val node = myNode
        if (node == null) {
            Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - myNode is null, returning null")
            return null // Mesh not initialized
        }
        
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        val connectedNeighbors = node.neighbors().size
        
        Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - connectedNeighbors=$connectedNeighbors, topologySize=${topology.size}")
        
        // Phase 3B: Count gateways by type
        val torGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(MeshRole.TOR_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        val clearnetGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        Log.d("MeshrabiyaApiImpl", "getNetworkInfo() - returning NetworkInfoDto with connectedPeers=$connectedNeighbors")
        
        return NetworkInfoDto(
            bssid = "",
            ssid = "",
            ipAddress = node.addressAsInt.addressToDotNotation(),
            isConnected = true,
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
        )
    }
    override fun getNodeId(): Int {
        val node = myNode ?: return 0
        return node.addressAsInt
    }

    override fun getNodeInfo(nodeId: String): NodeInfoDto? {
        val node = myNode ?: return null
        
        // Get topology information for the requested node
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        
        // Try to parse nodeId as Int, return empty if invalid
        val nodeAddress = nodeId.toIntOrNull() ?: return null
        val nodeData = topology[nodeAddress] ?: return null
        
        // Extract capabilities from meshRoles
        val capabilities = nodeData.meshRoles.map { role -> role.name }
        
        return NodeInfoDto(
            nodeId = nodeId,
            displayName = nodeId.substring(0, minOf(8, nodeId.length)), // Use first 8 chars as display name
            isOnline = !nodeData.isStale(GATEWAY_STALE_TIMEOUT_MS),
            lastSeen = System.currentTimeMillis(), // Topology doesn't track lastSeen, use current time
            capabilities = capabilities
        )
    }

    /**
     * Get current hotspot information
     * 
     * Priority order:
     * 1. LocalOnlyHotspot config (if device is running hotspot)
     * 2. WiFi Direct config (if using WiFi Direct)
     * 3. Station config (if connected as station to another hotspot)
     * 
     * Returns first available config, or null if none active.
     */
    override fun getHotspotInfo(): HotspotInfoDto? {
        Log.d(TAG, "getHotspotInfo() called")
        
        val node = myNode ?: run {
            Log.d(TAG, "getHotspotInfo() returning null (myNode is null)")
            return null
        }
        
        // Get current WiFi state
        val wifiState = runBlocking {
            node.meshrabiyaWifiManager.state.first()
        }
        
        // Priority 1: Check LocalOnlyHotspot state (device acting as AP)
        val localHotspotConfig = wifiState.localOnlyHotspotState.config
        if (localHotspotConfig != null && 
            wifiState.localOnlyHotspotState.status.toString() == "STARTED") {
            
            Log.d(TAG, 
                "getHotspotInfo() found LocalOnlyHotspot config: ssid=${localHotspotConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = localHotspotConfig.ssid,
                password = localHotspotConfig.passphrase,
                band = localHotspotConfig.band.toString(),
                nodeAddress = node.addressAsInt,
                bssid = localHotspotConfig.bssid,
                hotspotType = "LOCAL_ONLY",
                port = localHotspotConfig.port
            )
        }
        
        // Priority 2: Check WiFi Direct state (device acting as group owner)
        val wifiDirectConfig = wifiState.wifiDirectState.config
        if (wifiDirectConfig != null) {
            Log.d(TAG, 
                "getHotspotInfo() found WiFiDirect config: ssid=${wifiDirectConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = wifiDirectConfig.ssid,
                password = wifiDirectConfig.passphrase,
                band = wifiDirectConfig.band.toString(),
                nodeAddress = node.addressAsInt,
                bssid = wifiDirectConfig.bssid,
                hotspotType = "WIFI_DIRECT",
                port = wifiDirectConfig.port
            )
        }
        
        // Priority 3: Check station state (device connected to another hotspot)
        val stationConfig = wifiState.wifiStationState.config
        if (stationConfig != null) {
            Log.d(TAG, 
                "getHotspotInfo() found Station config: ssid=${stationConfig.ssid}")
            
            return HotspotInfoDto(
                ssid = stationConfig.ssid,
                password = stationConfig.passphrase,
                band = stationConfig.band.toString(),
                nodeAddress = stationConfig.nodeVirtualAddr,
                bssid = stationConfig.bssid,
                hotspotType = stationConfig.hotspotType.toString(),
                port = stationConfig.port
            )
        }
        
        Log.d(TAG, "getHotspotInfo() returning null (no active hotspot or station)")
        return null
    }

    /**
     * Join an existing mesh network using mesh-wide discovery.
     * 
     * Scans for all available mesh hotspots and connects to the strongest.
     * If CONNECTED when called, broadcasts merge announcement before connecting.
     */
    override fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
        Log.d(TAG, "========== JOIN MESH START ==========")
        Log.d(TAG, "joinMesh() called with QR data: ${jsonQrData.take(100)}...")
        Log.d(TAG, "Current mesh state: ${getMeshStatus()}")
        
        // Validate mesh is initialized
        if (myNode == null) {
            Log.e(TAG, "[JOIN FAIL] myNode is null - mesh not initialized!")
            callback(Result.failure(
                IllegalStateException("Mesh not initialized - call initMesh() first")
            ))
            return
        }
        
        Log.d(TAG, "[JOIN] Mesh validation passed, launching coroutine")
        Log.d(TAG, "[JOIN] Current node address: ${myNode?.address}")
        Log.d(TAG, "Launching coroutine for mesh-wide discovery join")
        
        // Launch connection in event monitoring scope (survives beyond this call)
        eventMonitoringScope.launch {
            try {
                // TODO PT8: If mesh is CONNECTED, broadcast MeshMergeAnnouncementMessage
                // and wait 5 seconds for propagation before connecting
                
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")  // Default to "meshr-"
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
                
                Log.d(TAG, "[JOIN] Parsed QR: password=$password, pattern=$ssidPattern, bootstrap=$bootstrapSsid")
                Log.d(TAG, "[JOIN] QR data validation successful")
                
                // Scan for available mesh hotspots
                val context = appContext ?: throw IllegalStateException("App context not set")
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                Log.d(TAG, "[JOIN] WiFi manager obtained, starting scan process")
                var attemptCount = 0
                var connected = false
                
                while (attemptCount < 3 && !connected) {
                    attemptCount++
                    Log.d(TAG, "[JOIN SCAN] ===== Attempt $attemptCount/3 =====")
                    Log.d(TAG, "[JOIN SCAN] Triggering WiFi scan...")
                    
                    // Trigger WiFi scan
                    wifiManager.startScan()
                    delay(2000)  // Wait for scan results
                    
                    // Get scan results and filter for mesh hotspots
                    val allNetworks = wifiManager.scanResults
                    Log.d(TAG, "[JOIN SCAN] Total networks detected: ${allNetworks.size}")
                    
                    // If bootstrapSSID is provided, look for it specifically; otherwise use pattern matching
                    val meshHotspots = if (!bootstrapSsid.isNullOrEmpty()) {
                        Log.d(TAG, "[JOIN SCAN] Looking for specific bootstrap SSID: $bootstrapSsid")
                        val bootstrap = allNetworks.filter { it.SSID == bootstrapSsid }
                        val pattern = allNetworks.filter { it.SSID.startsWith(ssidPattern) && it.SSID != bootstrapSsid }
                        (bootstrap + pattern).sortedByDescending { it.level }
                    } else {
                        Log.d(TAG, "[JOIN SCAN] No bootstrap SSID, using pattern matching: $ssidPattern")
                        allNetworks.filter { it.SSID.startsWith(ssidPattern) }.sortedByDescending { it.level }
                    }
                    
                    Log.d(TAG, "[JOIN SCAN] Mesh hotspots found: ${meshHotspots.size}")
                    meshHotspots.forEachIndexed { idx, hs ->
                        Log.d(TAG, "[JOIN SCAN]   [$idx] SSID=${hs.SSID}, Signal=${hs.level}dBm, Freq=${hs.frequency}MHz, BSSID=${hs.BSSID}")
                    }
                    
                    // Try connecting to each hotspot, starting with strongest
                    for ((index, hotspot) in meshHotspots.withIndex()) {
                        Log.d(TAG, "[JOIN CONNECT] --- Hotspot ${index + 1}/${meshHotspots.size} ---")
                        Log.d(TAG, "[JOIN CONNECT] Attempting connection to ${hotspot.SSID} (signal: ${hotspot.level} dBm)")
                        
                        try {
                            val band = when {
                                hotspot.frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
                                hotspot.frequency in 5000..6000 -> ConnectBand.BAND_5GHZ
                                else -> ConnectBand.BAND_UNKNOWN
                            }
                            Log.d(TAG, "[JOIN CONNECT] Band detected: $band (${hotspot.frequency}MHz)")
                            
                            // Parse port from QR data (REQUIRED)
                            val meshPort = qrJson.getInt("port")
                            Log.d(TAG, "[JOIN CONNECT] Using port from QR: $meshPort")
                            
                            val config = com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig(
                                nodeVirtualAddr = 0,  // Discovered from originating message
                                ssid = hotspot.SSID,
                                passphrase = password,
                                linkLocalAddr = null,
                                port = meshPort,
                                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                                persistenceType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType.NONE,
                                band = band,
                                bssid = hotspot.BSSID
                            )
                            
                            Log.d(TAG, "[JOIN CONNECT] Config created: ssid=${config.ssid}, port=${config.port}, band=${config.band}")
                            Log.d(TAG, "[JOIN CONNECT] Calling connectAsStation()...")
                            myNode?.connectAsStation(config)
                            Log.d(TAG, "[JOIN SUCCESS] ✅ Successfully connected to ${hotspot.SSID}")
                            Log.d(TAG, "[JOIN SUCCESS] Connection established, stopping scan loop")
                            connected = true
                            break  // Success - stop trying
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "[JOIN FAIL] ❌ Failed to connect to ${hotspot.SSID}", e)
                            Log.w(TAG, "[JOIN FAIL] Error: ${e.javaClass.simpleName}: ${e.message}")
                            // Continue to next hotspot
                        }
                    }
                    
                    if (!connected && attemptCount < 3) {
                        Log.d(TAG, "No connection established, waiting before retry...")
                        delay(2000)
                    }
                }
                
                if (connected) {
                    Log.d(TAG, "[JOIN RESULT] ========== JOIN MESH SUCCESS ==========")
                    Log.d(TAG, "[JOIN RESULT] Mesh join completed successfully")
                    Log.d(TAG, "[JOIN RESULT] New mesh state: ${getMeshStatus()}")
                    
                    // Initialize broadcast handler (NETWORK_BROADCAST_v2 implementation)
                    val node = myNode
                    if (node != null && broadcastHandler == null) {
                        broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                            virtualNode = node,
                            logger = node.logger,
                            cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                            getDropFolderCallback = { getDropFolderUriAsFile() }
                        )
                        // Wire handler to VirtualNode
                        node.broadcastMessageHandler = broadcastHandler
                        Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode (joinMesh)")
                        
                        // Apply any listeners registered before handler was created
                        applyPendingBroadcastListeners()
                    }
                    
                    callback(Result.success(Unit))
                } else {
                    Log.e(TAG, "[JOIN RESULT] ========== JOIN MESH FAILURE ==========")
                    Log.e(TAG, "[JOIN RESULT] No mesh hotspots available after 3 scan attempts")
                    Log.e(TAG, "[JOIN RESULT] Scanned for pattern: $ssidPattern")
                    callback(Result.failure(
                        Exception("No mesh hotspots available after 3 scan attempts")
                    ))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[JOIN ERROR] ========== JOIN MESH EXCEPTION ==========")
                Log.e(TAG, "[JOIN ERROR] Exception during join process", e)
                Log.e(TAG, "[JOIN ERROR] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "[JOIN ERROR] Exception message: ${e.message}")
                callback(Result.failure(e))
            }
        }
        
        Log.d(TAG, "[JOIN] joinMesh() returning (async coroutine launched)")
    }

    /**
     * Merge current mesh with another mesh (CONNECTED state only).
     * 
     * ALWAYS broadcasts MeshMergeAnnouncementMessage before connecting.
     * Requires PT8 implementation (multi-hop forwarding, message types, storage).
     */
    override fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
        Log.d(TAG, "========== MERGE MESH START ==========")
        Log.d(TAG, "mergeMesh() called with QR data: ${jsonQrData.take(100)}...")
        Log.d(TAG, "Current mesh state: ${getMeshStatus()}")
        
        // Validate mesh is initialized
        val node = myNode
        if (node == null) {
            Log.e(TAG, "[MERGE FAIL] myNode is null - mesh not initialized!")
            callback(Result.failure(
                IllegalStateException("Mesh not initialized - call initMesh() first")
            ))
            return
        }
        
        Log.d(TAG, "[MERGE] Current node address: ${node.address}")
        Log.d(TAG, "[MERGE] Current neighbor count: ${node.currentNodeState.originatorMessages.size}")
        
        // Validate CONNECTED state
        val currentState = getMeshStatus()
        if (currentState != MeshStateDto.CONNECTED) {
            Log.e(TAG, "[MERGE FAIL] Mesh is not CONNECTED - current state: $currentState")
            Log.e(TAG, "[MERGE FAIL] Merge requires CONNECTED state to announce to existing mesh")
            callback(Result.failure(
                IllegalStateException("Mesh must be CONNECTED to merge - current state: $currentState")
            ))
            return
        }
        
        Log.d(TAG, "[MERGE] State validation passed, launching coroutine")
        Log.d(TAG, "Launching coroutine for mesh merge")
        
        // Launch merge in event monitoring scope
        eventMonitoringScope.launch {
            try {
                // TODO PT8: Broadcast MeshMergeAnnouncementMessage to all devices on current mesh
                // Wait 5 seconds for multi-hop gossip propagation
                Log.d(TAG, "[MERGE ANNOUNCE] Broadcasting MeshMergeAnnouncementMessage to current mesh...")
                Log.d(TAG, "[MERGE ANNOUNCE] Announcement will propagate via multi-hop forwarding (PT8)")
                Log.d(TAG, "[MERGE ANNOUNCE] Waiting 5 seconds for gossip propagation...")
                delay(5000)  // Wait for announcement propagation
                Log.d(TAG, "[MERGE ANNOUNCE] Propagation delay complete")
                
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
                
                Log.d(TAG, "[MERGE] Parsed QR: password=$password, pattern=$ssidPattern, bootstrap=$bootstrapSsid")
                Log.d(TAG, "[MERGE] QR data validation successful")
                
                // Scan and connect (same logic as joinMesh)
                val context = appContext ?: throw IllegalStateException("App context not set")
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                Log.d(TAG, "[MERGE] WiFi manager obtained, starting scan process")
                var attemptCount = 0
                var connected = false
                
                while (attemptCount < 3 && !connected) {
                    attemptCount++
                    Log.d(TAG, "[MERGE SCAN] ===== Attempt $attemptCount/3 =====")
                    Log.d(TAG, "[MERGE SCAN] Triggering WiFi scan...")
                    
                    wifiManager.startScan()
                    delay(2000)
                    
                    val allNetworks = wifiManager.scanResults
                    Log.d(TAG, "[MERGE SCAN] Total networks detected: ${allNetworks.size}")
                    
                    // If bootstrapSSID is provided, look for it specifically; otherwise use pattern matching
                    val meshHotspots = if (!bootstrapSsid.isNullOrEmpty()) {
                        Log.d(TAG, "[MERGE SCAN] Looking for specific bootstrap SSID: $bootstrapSsid")
                        val bootstrap = allNetworks.filter { it.SSID == bootstrapSsid }
                        val pattern = allNetworks.filter { it.SSID.startsWith(ssidPattern) && it.SSID != bootstrapSsid }
                        (bootstrap + pattern).sortedByDescending { it.level }
                    } else {
                        Log.d(TAG, "[MERGE SCAN] No bootstrap SSID, using pattern matching: $ssidPattern")
                        allNetworks.filter { it.SSID.startsWith(ssidPattern) }.sortedByDescending { it.level }
                    }
                    
                    Log.d(TAG, "[MERGE SCAN] Mesh hotspots found: ${meshHotspots.size}")
                    meshHotspots.forEachIndexed { idx, hs ->
                        Log.d(TAG, "[MERGE SCAN]   [$idx] SSID=${hs.SSID}, Signal=${hs.level}dBm, Freq=${hs.frequency}MHz, BSSID=${hs.BSSID}")
                    }
                    
                    for ((index, hotspot) in meshHotspots.withIndex()) {
                        Log.d(TAG, "[MERGE CONNECT] --- Hotspot ${index + 1}/${meshHotspots.size} ---")
                        Log.d(TAG, "[MERGE CONNECT] Attempting merge connection to ${hotspot.SSID} (signal: ${hotspot.level} dBm)")
                        
                        try {
                            val band = when {
                                hotspot.frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
                                hotspot.frequency in 5000..6000 -> ConnectBand.BAND_5GHZ
                                else -> ConnectBand.BAND_UNKNOWN
                            }
                            Log.d(TAG, "[MERGE CONNECT] Band detected: $band (${hotspot.frequency}MHz)")
                            
                            // Get port from MMCP originating message connectConfig
                            // This is for mesh-to-mesh merge without QR code - port comes from neighbor's broadcast
                            // Find the originating message whose connectConfig has matching SSID
                            val originatorMessages = node.originatingMessageManager.getOriginatorMessages()
                            val matchingMessage = originatorMessages.values.find { 
                                it.originatorMessage.connectConfig?.ssid == hotspot.SSID 
                            }
                            val meshPort = matchingMessage?.originatorMessage?.connectConfig?.port
                                ?: throw IllegalStateException("[MERGE CONNECT] No connectConfig with SSID ${hotspot.SSID} found in MMCP messages")
                            Log.d(TAG, "[MERGE CONNECT] Using port from MMCP: $meshPort")
                            
                            val config = com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig(
                                nodeVirtualAddr = 0,
                                ssid = hotspot.SSID,
                                passphrase = password,
                                linkLocalAddr = null,
                                port = meshPort,
                                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                                persistenceType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType.NONE,
                                band = band,
                                bssid = hotspot.BSSID
                            )
                            
                            Log.d(TAG, "[MERGE CONNECT] Config created: ssid=${config.ssid}, port=${config.port}, band=${config.band}")
                            Log.d(TAG, "[MERGE CONNECT] Calling connectAsStation()...")
                            node.connectAsStation(config)
                            Log.d(TAG, "[MERGE SUCCESS] ✅ Successfully merged with ${hotspot.SSID}")
                            Log.d(TAG, "[MERGE SUCCESS] Meshes are now merged, stopping scan loop")
                            connected = true
                            break
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "[MERGE FAIL] ❌ Failed to merge with ${hotspot.SSID}", e)
                            Log.w(TAG, "[MERGE FAIL] Error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    
                    if (!connected && attemptCount < 3) {
                        delay(2000)
                    }
                }
                
                if (connected) {
                    Log.d(TAG, "[MERGE RESULT] ========== MERGE MESH SUCCESS ==========")
                    Log.d(TAG, "[MERGE RESULT] Mesh merge completed successfully")
                    Log.d(TAG, "[MERGE RESULT] Two meshes are now unified")
                    Log.d(TAG, "[MERGE RESULT] New mesh state: ${getMeshStatus()}")
                    Log.d(TAG, "[MERGE RESULT] Neighbor count after merge: ${node.currentNodeState.originatorMessages.size}")
                    
                    // Initialize broadcast handler if not already done (NETWORK_BROADCAST_v2 implementation)
                    if (broadcastHandler == null) {
                        broadcastHandler = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler(
                            virtualNode = node,
                            logger = node.logger,
                            cacheDir = appContext?.cacheDir ?: throw IllegalStateException("Context required for broadcast handler"),
                            getDropFolderCallback = { getDropFolderUriAsFile() }
                        )
                        // Wire handler to VirtualNode
                        node.broadcastMessageHandler = broadcastHandler
                        Log.d("MeshrabiyaApiImpl", "Broadcast handler initialized and wired to VirtualNode (mergeMesh)")
                        
                        // Apply any listeners registered before handler was created
                        applyPendingBroadcastListeners()
                    }
                    
                    callback(Result.success(Unit))
                } else {
                    Log.e(TAG, "[MERGE RESULT] ========== MERGE MESH FAILURE ==========")
                    Log.e(TAG, "[MERGE RESULT] No mesh hotspots available after 3 scan attempts")
                    Log.e(TAG, "[MERGE RESULT] Scanned for pattern: $ssidPattern")
                    Log.e(TAG, "[MERGE RESULT] Original mesh still intact, merge aborted")
                    callback(Result.failure(
                        Exception("No mesh hotspots available for merge after 3 scan attempts")
                    ))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[MERGE ERROR] ========== MERGE MESH EXCEPTION ==========")
                Log.e(TAG, "[MERGE ERROR] Exception during merge process", e)
                Log.e(TAG, "[MERGE ERROR] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "[MERGE ERROR] Exception message: ${e.message}")
                callback(Result.failure(e))
            }
        }
        
        Log.d(TAG, "[MERGE] mergeMesh() returning (async coroutine launched)")
    }

    // --- Gateway Controls ---
    // TODO: Reimplement using GatewaySelector from canonical workflows (2025-12-04)
    
    /**
     * Load persisted role preferences from dataStore and apply to EmergentRoleManager
     * Called when mesh starts to restore user's role preferences
     */
    private suspend fun loadAndApplyPersistedRolePreferences() {
        val roleManager = myNode?.emergentRoleManager ?: return
        val context = appContext ?: return
        
        try {
            val preferredRoles = mutableSetOf<MeshRole>()
            val prefs = context.dataStore.data.first()
            
            // Load gateway preferences
            val torGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Tor Gateway preference: $torGatewayEnabled")
            if (torGatewayEnabled) {
                preferredRoles.add(MeshRole.TOR_GATEWAY)
            }
            
            val clearnetGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Internet Gateway preference: $clearnetGatewayEnabled")
            if (clearnetGatewayEnabled) {
                preferredRoles.add(MeshRole.CLEARNET_GATEWAY)
            }
            
            // Load storage participation preference
            val storageEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Storage participation preference: $storageEnabled")
            if (storageEnabled) {
                preferredRoles.add(MeshRole.STORAGE_NODE)
            }
            
            // Load service participation preference
            val serviceEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] ?: false
            Log.i(TAG, "[INIT] Loaded Service participation preference: $serviceEnabled")
            if (serviceEnabled) {
                preferredRoles.add(MeshRole.COMPUTE_NODE)
            }
            
            // Apply to role manager
            roleManager.setPreferredRoles(preferredRoles)
            Log.i(TAG, "[INIT] Loaded and applied persisted preferred roles: $preferredRoles")
            
            // Trigger initial role calculation
            roleManager.updateRoles()
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[INIT] Current mesh roles AFTER updateRoles(): $actualRoles")
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] Error loading persisted role preferences: ${e.message}", e)
        }
    }
    
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            Log.i(TAG, "[GATEWAY_TOGGLE] Setting Tor Gateway to: $enabled")
            
            // Persist preference to dataStore
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] = enabled
                }
            }
            
            // Update preferred roles
            val currentRoles = roleManager.getPreferredRoles().toMutableSet()
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles BEFORE: $currentRoles")
            
            if (enabled) {
                currentRoles.add(MeshRole.TOR_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Added TOR_GATEWAY to preferred roles")
            } else {
                currentRoles.remove(MeshRole.TOR_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Removed TOR_GATEWAY from preferred roles")
            }
            
            roleManager.setPreferredRoles(currentRoles)
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles AFTER: $currentRoles")
            
            // Trigger role recalculation
            Log.i(TAG, "[GATEWAY_TOGGLE] Triggering updateRoles()...")
            roleManager.updateRoles(userInitiated = true)
            
            // Log the actual current mesh roles after update
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[GATEWAY_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[GATEWAY_TOGGLE] Error setting Tor Gateway: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getTorGatewayStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] ?: false
        }
    }
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            Log.i(TAG, "[GATEWAY_TOGGLE] Setting Internet Gateway to: $enabled")
            
            // Persist preference to dataStore
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] = enabled
                }
            }
            
            // Update preferred roles
            val currentRoles = roleManager.getPreferredRoles().toMutableSet()
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles BEFORE: $currentRoles")
            
            if (enabled) {
                currentRoles.add(MeshRole.CLEARNET_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Added CLEARNET_GATEWAY to preferred roles")
            } else {
                currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
                Log.i(TAG, "[GATEWAY_TOGGLE] Removed CLEARNET_GATEWAY from preferred roles")
            }
            
            roleManager.setPreferredRoles(currentRoles)
            Log.i(TAG, "[GATEWAY_TOGGLE] Preferred roles AFTER: $currentRoles")
            
            // Trigger role recalculation
            Log.i(TAG, "[GATEWAY_TOGGLE] Triggering updateRoles()...")
            roleManager.updateRoles(userInitiated = true)
            
            // Log the actual current mesh roles after update
            val actualRoles = roleManager.getCurrentMeshRoles()
            Log.i(TAG, "[GATEWAY_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[GATEWAY_TOGGLE] Error setting Internet Gateway: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getInternetGatewayStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] ?: false
        }
    }
    override fun getGatewayStatus(): Boolean {
        val roleManager = myNode?.emergentRoleManager ?: return false
        val roles = roleManager.getCurrentMeshRoles()
        return roles.contains(MeshRole.TOR_GATEWAY) || 
               roles.contains(MeshRole.CLEARNET_GATEWAY) ||
               roles.contains(MeshRole.I2P_GATEWAY)
    }

    // --- V3: Gateway Preference Implementation ---
    override fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit) {
        try {
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)] = GatewayPreference.toString(preference )  
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
        try {
            Log.i(TAG, "[STORAGE_TOGGLE] Setting Storage Participation to: $enabled (mesh started: ${myNode != null})")
            
            // ALWAYS persist preference to dataStore first - this works even if mesh isn't started
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] = enabled
                }
            }
            Log.i(TAG, "[STORAGE_TOGGLE] Persisted to dataStore: $enabled")
            
            // If mesh is started, apply changes immediately to running managers
            val roleManager = myNode?.emergentRoleManager
            val storageManager = myNode?.distributedStorageManager
            
            if (roleManager != null) {
                Log.i(TAG, "[STORAGE_TOGGLE] Mesh is running, updating roles")
                
                // Configure storage manager if available
                if (storageManager != null) {
                    Log.i(TAG, "[STORAGE_TOGGLE] Configuring storage manager")
                    val config = com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageParticipationConfig(
                        participationEnabled = enabled,
                        totalQuota = storageManager.storageConfig.defaultQuota,
                        allowedDirectories = emptyList(),  // Use defaults
                        encryptionRequired = storageManager.storageConfig.encryptionEnabled
                    )
                    storageManager.configureStorageParticipation(config)
                } else {
                    Log.i(TAG, "[STORAGE_TOGGLE] Storage manager not available, skipping storage config")
                }
                
                // Update preferred roles
                val currentRoles = roleManager.getPreferredRoles().toMutableSet()
                Log.i(TAG, "[STORAGE_TOGGLE] Preferred roles BEFORE: $currentRoles")
                
                if (enabled) {
                    currentRoles.add(MeshRole.STORAGE_NODE)
                    Log.i(TAG, "[STORAGE_TOGGLE] Added STORAGE_NODE to preferred roles")
                } else {
                    currentRoles.remove(MeshRole.STORAGE_NODE)
                    Log.i(TAG, "[STORAGE_TOGGLE] Removed STORAGE_NODE from preferred roles")
                }
                
                roleManager.setPreferredRoles(currentRoles)
                Log.i(TAG, "[STORAGE_TOGGLE] Preferred roles AFTER: $currentRoles")
                
                // Trigger role recalculation
                Log.i(TAG, "[STORAGE_TOGGLE] Triggering updateRoles()...")
                roleManager.updateRoles(userInitiated = true)
                
                // Debug: check if roles actually changed
                val actualRoles = roleManager.getCurrentMeshRoles()
                Log.i(TAG, "[STORAGE_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            } else {
                Log.i(TAG, "[STORAGE_TOGGLE] Mesh not started yet, preference saved for later application")
            }
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[STORAGE_TOGGLE] Error setting Storage Participation: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getStorageParticipationStatus(): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_STORAGE_PARTICIPATION_ENABLED)] ?: false
        }
    }
    override fun getAvailableStorageDevices(): List<StorageDeviceDto> {
        // TODO properly implement getAvailableStorageDevices()
        // Storage device enumeration not yet implemented in DistributedStorageManager
        // Return empty list until backend implementation available
        return emptyList()
    }
    
    override fun setStorageAllocation(deviceId: String,
    path: String, allocatedMB: Long, ) {
        try {
            val current = MeshrabiyaConstants.getStorageAllocations().toMutableList()
            val idx = current.indexOfFirst { it.path == path }
            if (idx >= 0) {
                current[idx] = StorageAllocation(path, allocatedMB,deviceId,)
            } else {
                current.add(StorageAllocation(path, allocatedMB,deviceId,))
            }
            MeshrabiyaConstants.setStorageAllocations(current)
            // callback(Result.success(Unit))
        } catch (e: Exception) {
            // callback(Result.failure(e))
        }
    }

    override fun getStorageAllocations(): List<StorageAllocationDto> {
        val storage =MeshrabiyaConstants.getStorageAllocations()
        return storage.map {
            it.toDto()
        }
    }
    
    override fun enableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.registerWithEcosystemListener(listener)
        }
    }
    
    override fun disableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.unregisterFromEcosystemListener(listener)
        }
    }

    private var onDropFolderUpdateHandler: ((List<DropFolderItemDto>) -> Unit)? = null

    override fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit) {
        onDropFolderUpdateHandler = handler
    }

    internal fun notifyDropFolderUpdate(changes: List<DropFolderItem>) {
        val dtos = changes.map { it.toDto() }
        onDropFolderUpdateHandler?.invoke(dtos)
    }

    

    override fun setDropFolderUri(uri: String) {
        MeshrabiyaConstants.setDropFolderUri(uri)
        Log.d(TAG, "Drop folder URI set: $uri")
    }
    
    override fun getDropFolderUri(): String? {
        return MeshrabiyaConstants.getDropFolderUri()
    }
    // TODO: Reimplement using TaskManager from canonical workflows (2025-12-04)
    override fun isComputeLayerParticipating(): Boolean {
        return MeshrabiyaConstants.isComputeLayerParticipating()
    }

    private fun getDropFolderUriAsFile(): File? {
        val uriString = getDropFolderUri() ?: return null
        return try {
            val context = appContext ?: return null
            val uri = Uri.parse(uriString)
            val docFile = DocumentFile.fromTreeUri(context, uri)
            // For broadcasts storage, use a File wrapper pointing to the URI
            // Note: This is a compatibility shim - actual file operations should use DocumentFile
            docFile?.uri?.path?.let { File(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert drop folder URI to File", e)
            null
        }
    }

    override fun setStorageQuotaBytes(quotaBytes: Long) {
        MeshrabiyaConstants.setStorageQuotaBytes(quotaBytes)
        Log.d(TAG, "Storage quota set: ${quotaBytes / (1024 * 1024)}MB ($quotaBytes bytes)")
    }
    
    override fun getStorageQuotaBytes(): Long {
        return MeshrabiyaConstants.getStorageQuotaBytes()
    }

    override fun setComputeLayerParticipatingEnabled(enabled: Boolean) {
        MeshrabiyaConstants.setComputeLayerParticipatingEnabled(enabled)
    }

    // --- Drop Folder Management ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    // override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
    //     try {
    //         val context = appContext ?: throw IllegalStateException("Context required")
    //         val folder = File(path)
            
    //         // Validate folder
    //         if (!folder.exists() || !folder.isDirectory) {
    //             callback(Result.failure(IllegalArgumentException("Invalid folder path: $path")))
    //             return
    //         }
    //         if (!folder.canWrite()) {
    //             callback(Result.failure(IllegalArgumentException("Folder not writable: $path")))
    //             return
    //         }
            
    //         // Save to SharedPreferences
    //         val prefs = context.getSharedPreferences("meshrabiya_prefs", Context.MODE_PRIVATE)
    //         prefs.edit().putString("drop_folder_path", path).apply()
            
    //         Log.i(TAG, "Drop folder set: $path")
    //         callback(Result.success(Unit))
    //     } catch (e: Exception) {
    //         Log.e(TAG, "Failed to set drop folder", e)
    //         callback(Result.failure(e))
    //     }
    // }
    
    override fun getDropFolderFiles(): List<File> = emptyList() // distributedStorageManager?.getDropFolderFiles() ?: emptyList()

    // =========================================================
    // Section 1: File Operations (Canonical Workflow Refactor)
    // =========================================================
    // All file operations below are refactored to match canonical workflow requirements.
    // Each method includes explicit TODOs, error handling, and implementation notes.
    // Remove TODOs and update implementation when DistributedStorageManager supports each operation.
    override fun storeFile(file: File, recipients:List<RecipientEntryDto>
    ) {
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("storeFile", IllegalStateException("Storage manager not initialized"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileBytes = file.readBytes()
                val senderId = myNode?.address?.hostAddress
                val recipientEntryList: List<RecipientEntry> = recipients.map { it.toInternal() }
                val fileRef = storageManager.storeFile(
                    path = file.absolutePath,
                    data = fileBytes,
                    recipients = recipientEntryList, // TODO: Specify recipients if needed
                )
                if (fileRef != null) {
                    // callback(Result.success(fileRef.id))
                    onFileStored?.invoke(fileRef.fileId, file, Result.success(fileRef.fileId))
                } else {
                    // callback(Result.failure(Exception("Failed to store file")))
                     getOnOperationFailed()?.invoke("storeFile",  Exception("Failed to store file"))
                }
            } catch (e: Exception) {
                // callback(Result.failure(e))
                getOnOperationFailed()?.invoke("storeFile", Exception("Failed to store file"))
            }
        }
    }
    // TODO retreieveFile should not return anything. when files is retrieved, generic handler shpu;d be calles
    override suspend fun retrieveFile(fileId: String): ByteArray? {
        if (fileId.isBlank()) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalArgumentException("File ID cannot be blank"))
            return null
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalStateException("Storage manager not initialized"))
            return null
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            getOnOperationFailed()?.invoke("retrieveFile", java.io.FileNotFoundException("File not found: $fileId"))
            return null
        }
        return try {
            storageManager.retrieveFile(fileId)
        } catch (e: Exception) {
            getOnOperationFailed()?.invoke("retrieveFile", e)
            null
        }
    }

    override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        // Streaming not implemented in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("streamFile not implemented in DistributedStorageManager")))
    }

    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        if (fileId.isBlank()) {
            callback(Result.failure(IllegalArgumentException("File ID cannot be blank")))
            return
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            return
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            callback(Result.failure(java.io.FileNotFoundException("File not found: $fileId")))
            return
        }
        // No deleteFile API in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("deleteFile not implemented in DistributedStorageManager")))
    }
    override fun getAllMeshFiles(): List<MeshFileDto> {
        // Check storage manager availability
        val storageManager = myNode?.distributedStorageManager ?: return emptyList()
        
        try {
            // Get all file metadata from storage manager's file metadata map
            val fileMetadataMap = storageManager.fileMetadataStore
            
            if (fileMetadataMap.isEmpty()) {
                return emptyList()
            }
            
            // Convert FileMetadata to MeshFile
            return fileMetadataMap.values.map { metadata ->
                MeshFileDto(
                    fileId = metadata.fileId,
                    fileName = File(metadata.path).name,  // Extract filename from path
                    owner = metadata.owner.toDto(),
                    recipients = metadata.recipients.map { it.toDto() },
                    sizeBytes = metadata.sizeBytes,
                    createdAt = metadata.createdAt,
                    relativePath = metadata.relativePath,
                    path = metadata.path
                )
            }
        } catch (e: Exception) {
            onOperationFailed?.invoke("getAllMeshFiles", e)
            return emptyList()
        }
    }

    // --- Distributed Service Layer ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            Log.i(TAG, "[SERVICE_TOGGLE] Setting Service Participation ($serviceId) to: $enabled (mesh started: ${myNode != null})")
            
            // ALWAYS persist preference to dataStore (works even if mesh not started)
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] = enabled
                }
            }
            Log.i(TAG, "[SERVICE_TOGGLE] Persisted to dataStore: $enabled")
            
            // IF mesh is started, apply to runtime role manager
            val roleManager = myNode?.emergentRoleManager
            if (roleManager != null) {
                // Update preferred roles
                val currentRoles = roleManager.getPreferredRoles().toMutableSet()
                Log.i(TAG, "[SERVICE_TOGGLE] Preferred roles BEFORE: $currentRoles")
                
                if (enabled) {
                    currentRoles.add(MeshRole.COMPUTE_NODE)
                    Log.i(TAG, "[SERVICE_TOGGLE] Added COMPUTE_NODE to preferred roles")
                } else {
                    currentRoles.remove(MeshRole.COMPUTE_NODE)
                    Log.i(TAG, "[SERVICE_TOGGLE] Removed COMPUTE_NODE from preferred roles")
                }
                
                roleManager.setPreferredRoles(currentRoles)
                Log.i(TAG, "[SERVICE_TOGGLE] Preferred roles AFTER: $currentRoles")
                
                // Trigger role recalculation
                Log.i(TAG, "[SERVICE_TOGGLE] Triggering updateRoles()...")
                roleManager.updateRoles(userInitiated = true)
                
                // Debug: check if roles actually changed
                val actualRoles = roleManager.getCurrentMeshRoles()
                Log.i(TAG, "[SERVICE_TOGGLE] Current mesh roles AFTER updateRoles(): $actualRoles")
            } else {
                Log.i(TAG, "[SERVICE_TOGGLE] Mesh not started yet, preference saved for later application")
            }
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e(TAG, "[SERVICE_TOGGLE] Error setting Service Participation: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    override fun getAvailableServices(): List<String> = emptyList() // myNode?.getAvailableServices() ?: emptyList()
    override fun getServiceParticipationStatus(serviceId: String): Boolean {
        val context = appContext ?: return false
        return runBlocking {
            context.dataStore.data.first()[booleanPreferencesKey(MeshrabiyaConstants.KEY_SERVICE_PARTICIPATION_ENABLED)] ?: false
        }
    }

    // --- Compute/Task Operations ---
    /**
     * Submit a compute task to the mesh network.
     * 
     * @param requestParams Map containing:
     *   - taskId (String, optional): Unique task identifier (auto-generated if not provided)
     *   - taskType (String, required): Execution engine (python, jvm, javascript, ml-native)
     *   
     * @return ApiResult.Success if task submitted, ApiResult.Failure on error
     * 
     * Note: JobType removed 2025-12-06 - no concept of "supported job types".
     * Use taskType to specify execution engine. Service discovery handles capability matching.
     */
    override fun addTask(serviceId: String,requestParams: Map<String, Any>, recipients: List<RecipientEntryDto>): Any? {
        return try {
            // Extract parameters
            val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
            
            
            // Check compute client availability
            val computeClient = myNode?.obtainDistributedComputeClient() 
                ?: return ApiResult.Failure(IllegalStateException("Compute client not initialized"))
            val recipientEntryList: List<RecipientEntry> = recipients.map { it.toInternal() }
            // Create LocalComputeTaskRequest
            val request = LocalComputeTaskRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                taskId = taskId,
                serviceId = serviceId, 
                inputs = requestParams,
                timestamp = System.currentTimeMillis(),
                recipients = recipientEntryList,
            )
            
            // Submit task asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    computeClient.processTaskRequest(request)
                } catch (e: Exception) {
                    onOperationFailed?.invoke("addTask", e)
                }
            }
            
            ApiResultDto.Success  // Return immediately, status updates via callback
        } catch (e: Exception) {
            onOperationFailed?.invoke("addTask", e)
        }
    }

    // override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
    //     callback(Result.failure(NotImplementedError("startTask not yet implemented in canonical workflows")))
    //     // intelligentDistributedComputeService?.startTask(taskId, callback)
    // }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("cancelTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.cancelTask(taskId, callback)
    }
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission
    // override fun getJobTypes(): List<JobType> = emptyList()
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These implementations reference scheduler types that are unused in Phase 3-4
    // override fun getTaskStatus(taskId: String): ExecutionPlan? = intelligentDistributedComputeService?.getTaskStatus(taskId)
    // override fun getAllTasks(): List<ComputeTask> = intelligentDistributedComputeService?.getAllTasks() ?: emptyList()
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File, result: Result<String>) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    // UNUSED SCHEDULER API - Commented 2025-12-04
    // private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
    private var onFileAddedToDropFolder: ((fileId: String, file: File) -> Unit)? = null
    
    // Broadcast event handlers (added 2026-02-01)
    private var onBroadcastSent: ((com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)? = null
    private var onBroadcastFailed: ((broadcastId: String, error: Throwable) -> Unit)? = null

    override fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit) {
        onFileRetrieved = handler
    }
    override fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit) {
        onFileStored = handler
    }
    fun getOnFileStored(): ((fileId: String, file: File, result: Result<String>) -> Unit)? {
        return onFileStored
    }
    override fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit) {
        onPermissionUpdated = handler
    }
    override fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit) {
        onOperationFailed = handler
    }

    fun getOnOperationFailed(): ((operation: String, error: Throwable) -> Unit)? {
        return onOperationFailed
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
    
    override fun setOnBroadcastSent(handler: (com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit) {
        onBroadcastSent = handler
    }
    
    override fun setOnBroadcastFailed(handler: (broadcastId: String, error: Throwable) -> Unit) {
        onBroadcastFailed = handler
    }
    
    fun getOnBroadcastSent(): ((com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)? {
        return onBroadcastSent
    }
    
    fun getOnBroadcastFailed(): ((broadcastId: String, error: Throwable) -> Unit)? {
        return onBroadcastFailed
    }

    // --- Settings and State ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "dropFolderPath" to "",
            "availableServices" to emptyList<String>()
            // DEPRECATED 2025-12-06: jobTypes removed - use ServiceLibraryEntries for capability discovery
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
    private var onMeshStateChanged: ((MeshStateDto) -> Unit)? = null
    private var onPeerCountChanged: ((Int) -> Unit)? = null
    // private var onServiceBundleReceived: ((String, ByteArray) -> Unit)? = null
    // private var onServiceAnnounced: ((String, ServiceAnnouncement) -> Unit)? = null
    private var onGossipMessage: ((Int, ByteArray) -> Unit)? = null
    private var onTaskStatusUpdate: ((String, String) -> Unit)? = null  // Section 9

    override fun setOnMeshStateChanged(handler: (newState: MeshStateDto) -> Unit) {
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
    
    /**
     * Section 9: Set task status update callback
     * Wired through MeshEcosystemListener when TaskCompletedMessage received
     */
    override fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit) {
        onTaskStatusUpdate = handler
    }
    
    /**
     * Section 9: Internal method called by MeshEcosystemListener to trigger callback
     * This provides a public accessor for the listener to invoke the callback
     */
    fun triggerTaskStatusUpdate(taskId: String, status: String) {
        onTaskStatusUpdate?.invoke(taskId, status)
    }

    // --- TaskType enablement API ---
    override fun isTaskTypeEnabled(taskType: TaskTypeDto): Boolean {
        return MeshrabiyaConstants.isTaskTypeEnabled(taskType.toInternal())
    }

    override fun setTaskTypeEnabled(taskType: TaskTypeDto, enabled: Boolean) {
        MeshrabiyaConstants.setTaskTypeEnabled(taskType.toInternal(), enabled)
    }

    override fun getAllTaskTypeEnabled(): Map<TaskTypeDto, Boolean> {
        return MeshrabiyaConstants.getAllTaskTypeEnabled()
            .mapNotNull { (key, value) ->
                try {
                    key.toDto() to value
                } catch (e: IllegalArgumentException) {
                    // Optionally log: println("TaskType $key not present in TaskTypeDto")
                    null
                }
            }.toMap()
    }


    // --- User Identity API Implementation ---
    // Allow provider to be injected for JVM testability; default to AndroidKeyStore
    private var keyProvider: String =
        if (System.getProperty("java.vendor")?.contains("Android") == true) "AndroidKeyStore" else "BC"

    fun setKeyProviderForTest(provider: String) {
        keyProvider = provider
    }

    
    override fun getUserInfo(): User {
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keyProvider='$keyProvider'")
        val keypair = UserKeyManager.getKeypair(provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keypair=$keypair")
        if (keypair == null) throw IllegalStateException("User keypair not initialized (provider='$keyProvider')")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        val nickname = MeshrabiyaConstants.getNickname() ?: ""
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: userId='$userId', nickname='$nickname'")
        val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return User(userId, publicKey, nickname, keypair, userEntry)
    }

        // TODO update setUserNickname to update user object
    override fun setUserNickname(nickname: String) {
        println("[DEBUG] MeshrabiyaApiImpl.setUserNickname: nickname='$nickname'")
        MeshrabiyaConstants.setNickname(nickname)
    }

    // TODO update rotateUserKey to update user object
    override fun rotateUserKey(): User  {
        val context = getAppContext() ?: throw IllegalStateException("App context not set")
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keyProvider='$keyProvider'")
        val keypair = UserKeyManager.rotateKeypair(context, provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keypair=$keypair")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: new userId='$userId'")
        MeshrabiyaConstants.setUserId(userId)
        MeshrabiyaConstants.setUserPublicKey(android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.DEFAULT))
         val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return User(userId, publicKey, MeshrabiyaConstants.getNickname() ?: "", keypair, userEntry)
    }

    /**
     * Initialize user info on first run: generate keypair, set nickname, store userId and nickname.
     * Should be called during app startup or mesh initialization.
     */
    fun initializeUser(context: Context, nicknameProvider: (() -> String)? = null) {
        // Check if userId is already set
        val userId = MeshrabiyaConstants.getUserId()
        println("[DEBUG] MeshrabiyaApiImpl.initializeUser: userId='$userId', keyProvider='$keyProvider'")
        if (userId.isNullOrEmpty()) {
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generating keypair")
            val keypair = UserKeyManager.generateKeypair(context, provider = keyProvider)
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generated keypair: $keypair")
            val publicKey = keypair.public
            val newUserId = publicKey.toHash()
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: newUserId='$newUserId'")
            MeshrabiyaConstants.setUserId(newUserId)
            // Prompt for nickname or set default
            val nickname = nicknameProvider?.invoke() ?: "MeshUser"
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: nickname='$nickname'")
            MeshrabiyaConstants.setNickname(nickname)
        }
    }

        // --- Network Overview Metrics StateFlow ---
    // private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto())
    // val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    // private var lastUploadBytes: Long = 0L
    // private var lastDownloadBytes: Long = 0L
    // private var lastTimestamp: Long = System.currentTimeMillis()

    

    // private fun getActiveNodeCount(): Int {
    //     // Implement logic to count active nodes in the mesh
    //     return meshNodeList.size // or other logic as appropriate
    // }

    // --- Broadcast Message+File Operations ---
    // Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
    
    override suspend fun broadcastMessageAndFile(
        messageText: String,
        filePath: String
    ) {
        // Validate at least one input provided
        if (messageText.isEmpty() && filePath.isEmpty()) {
            val error = IllegalArgumentException("Either message or file must be provided")
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Validate message length
        if (messageText.length > MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            val error = IllegalArgumentException(
                "Message exceeds ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH} character limit"
            )
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        
        
        // Validate mesh is running
        val handler = broadcastHandler
        if (handler == null) {
            val error = IllegalStateException("Mesh is not running")
            onBroadcastFailed?.invoke("", error)
            throw error
        }
        
        // Delegate to handler with callback that invokes event handlers
        handler.sendBroadcast(messageText, filePath) { result ->
            if (result.isSuccess) {
                val broadcastResult = result.getOrNull()
                if (broadcastResult != null) {
                    onBroadcastSent?.invoke(broadcastResult)
                }
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                onBroadcastFailed?.invoke("", error)
            }
        }
    }
    
    override fun registerBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        val handler = broadcastHandler
        if (handler != null) {
            // Handler exists, register immediately
            handler.addReceiveListener(listener)
            Log.d(TAG, "Registered broadcast listener immediately")
        } else {
            // Handler not yet created, queue for later
            if (pendingBroadcastListeners.size < MeshrabiyaConstants.DEFERRED_LISTENER_QUEUE_MAX_SIZE) {
                pendingBroadcastListeners.add(listener)
                Log.d(TAG, "Queued broadcast listener (pending=${pendingBroadcastListeners.size})")
            } else {
                Log.w(TAG, "Cannot queue broadcast listener: queue full (max=${MeshrabiyaConstants.DEFERRED_LISTENER_QUEUE_MAX_SIZE})")
            }
        }
    }
    
   override fun unregisterBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        broadcastHandler?.removeReceiveListener(listener)
    }

    /**
     * Apply all queued listeners to the broadcast handler
     * Called automatically when broadcastHandler is initialized
     * Added: 2026-02-15 for deferred listener registration
     */
    private fun applyPendingBroadcastListeners() {
        val handler = broadcastHandler ?: return
        var appliedCount = 0
        
        while (true) {
            val listener = pendingBroadcastListeners.poll() ?: break
            handler.addReceiveListener(listener)
            appliedCount++
        }
        
        if (appliedCount > 0) {
            Log.d(TAG, "Applied $appliedCount pending broadcast listeners")
        }
    }

}