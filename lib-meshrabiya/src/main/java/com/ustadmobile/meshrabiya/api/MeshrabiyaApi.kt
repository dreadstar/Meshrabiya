package com.ustadmobile.meshrabiya.api
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
    
import java.io.File
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import kotlinx.serialization.Serializable
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// Phase 3-4 uses direct broadcast-response pattern (processTaskRequest → node selection)
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.LocalNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.api.model.*

// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Unified API interface for Meshrabiya module.
 * Exposes all key operations, state, and event registration for UI/control layers.
 */
interface MeshrabiyaApi {
    val meshStatusFlow: kotlinx.coroutines.flow.StateFlow<MeshStateDto>
    val networkOverviewMetricsFlow: kotlinx.coroutines.flow.StateFlow<NetworkOverviewMetricsDto>

    /**
     * Provide application context to the Meshrabiya core (for use by TaskManager, etc.)
     */
    fun provideAppContext(context: Context)

    /**
     * Retrieve the application context for internal use (TaskManager, etc.)
     */
    fun getAppContext(): Context?

    // --- Mesh Initialization ---
    fun initMesh(context: Context)

    // --- Mesh State & Network Info ---
    // fun getNodeRole(): Byte
    fun getNodeRoleNames(): List<String>
    fun getFitnessScore(): Float
    fun getConnectionUri(): String
    fun getLocalNodeState(): LocalNodeStateDto
    fun getNeighbors(): List<NeighborInfoDto> 
    fun getHopCountToNode(nodeId: Int): Int?
    fun getConnectLink(): String?
    fun getConnectLinkFlow(): Flow<String?>

    // --- Mesh Network Controls ---
    fun startMesh(callback: (Result<Unit>) -> Unit)
    fun stopMesh(callback: (Result<Unit>) -> Unit)
    fun getMeshStatus(): MeshStateDto
    fun refreshMeshStatus()
    fun getPeerCount(): Int
    fun getNetworkInfo(): NetworkInfoDto?
    fun getNodeInfo(nodeId: String): NodeInfoDto?
    fun getNodeId(): Int
    
    /**
     * Get current hotspot information (credentials, band, node address)
     * 
     * Returns network credentials that peers can use to join this mesh.
     * The hotspot must be active (mesh CONNECTED state) to retrieve valid credentials.
     * 
     * **Android Version Differences:**
     * - Android 13+: Managed hotspot with predictable SSID ("meshr-<hex>") and shared password
     * - Android 8-12: LocalOnlyHotspot with random SSID/password
     * 
     * @return HotspotInfoDto containing ssid, password, band, nodeAddress, bssid, hotspotType
     *         Returns null if mesh is not in CONNECTED state (hotspot not active)
     */
    fun getHotspotInfo(): HotspotInfoDto?
    
    /**
     * Join an existing mesh network using mesh-wide discovery
     * 
     * This method scans for ALL available mesh hotspots and connects to the strongest one.
     * This enables resilient joining - if the QR code generator's hotspot is offline,
     * the device will automatically connect to any other available mesh hotspot.
     * 
     * This method can be called from ANY mesh state:
     * - DISCONNECTED: Device will initialize mesh and connect as station
     * - CONNECTING: Will switch to new network
     * - CONNECTED: Will broadcast merge announcement, then add station connection
     * 
     * **Process:**
     * 1. IF CONNECTED: Broadcast merge announcement to current mesh (5s delay for propagation)
     * 2. Parses JSON QR code data (password, SSID pattern)
     * 3. Scans for all SSIDs matching "meshr-*"
     * 4. Sorts by signal strength and attempts connection (strongest first)
     * 5. Retries scan up to 3 times if no hotspots found
     * 6. Stores password for automatic reconnection if hotspot changes
     * 
     * @param jsonQrData JSON string from scanned QR code containing:
     *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
     * @param callback Result callback invoked on completion
     *                 Success(Unit) on successful connection to any mesh hotspot
     *                 Failure(exception) if no mesh hotspots available after retries
     */
    fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)
    
    /**
     * Merge current mesh with another mesh network (CONNECTED state only)
     * 
     * **USE CASE: Merging two existing meshes**
     * - Device is ALREADY connected to a mesh
     * - User scans QR of another mesh to merge
     * - ALWAYS broadcasts merge announcement first
     * 
     * **ORGANIC MESH MERGE WORKFLOW (PT8):**
     * 1. Broadcast MeshMergeAnnouncement to ALL devices on current mesh
     * 2. Wait 5 seconds for multi-hop gossip propagation
     * 3. Connect this device to target mesh (add station connection)
     * 4. Other devices receive announcement and independently decide to join
     * 5. Idempotent check prevents duplicate joins (same SSID/password)
     * 
     * **Key Differences from joinMesh():**
     * - mergeMesh() REQUIRES CONNECTED state (returns error if DISCONNECTED)
     * - ALWAYS broadcasts announcement (joinMesh() only broadcasts if CONNECTED)
     * - Clearer user intent: "I want to merge two meshes"
     * - UI: Separate "Merge Mesh" button (enabled only when CONNECTED)
     * 
     * **Requirements:**
     * - Multi-hop forwarding MUST be enabled (VirtualNode.kt Lines 702-722 uncommented)
     * - MeshMergeAnnouncementMessage must be implemented (PT8 Change 2)
     * - MeshConfigStorage must be implemented (PT8 Change 4)
     * - EmergentRoleManager must forward broadcasts (MESH_ROUTER role)
     * 
     * See PT8 for complete implementation details.
     * See MESH_GROUP_MERGING_RESEARCH_FINDINGS.md for organic merge strategy.
     * 
     * @param jsonQrData JSON string from scanned QR code containing:
     *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
     * @param callback Result callback invoked on completion
     *                 Success(Unit) on successful merge (announcement broadcast + connection)
     *                 Failure(exception) if not CONNECTED, no hotspots found, or connection fails
     */
    fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)
    
    // --- Proxy Controls ---
    fun setProxy(host: String, port: Int)
    fun setProxyActive(active: Boolean)

    // --- Gateway Controls ---
    fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getTorGatewayStatus(): Boolean
    fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getInternetGatewayStatus(): Boolean
    fun getGatewayStatus(): Boolean

    // --- V3: Gateway Preference Controls ---
    /**
     * Set the global gateway preference for internet-bound traffic.
     * 
     * **Precedence:** Per-app VPN rules (Orbot SharedPreferences "PrefTord") supersede this preference.
     * 
     * @param preference Gateway routing policy (TOR_ONLY, CLEARNET_ONLY, EITHER)
     * @param callback Result callback (Success if saved, Failure on error)
     */
    fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)

    /**
     * Get the current global gateway preference.
     * 
     * @return Current gateway preference (TOR_ONLY, CLEARNET_ONLY, or EITHER)
     */
    fun getGatewayPreference(): GatewayPreference

    /**
     * Query current Tor daemon status from Orbot.
     * 
     * **Implementation:** Reads last known status from TorStatusMonitor BroadcastReceiver.
     * 
     * @return true if Tor is running ("ON" status), false otherwise
     */
    fun isTorActive(): Boolean

    // --- Storage Participation ---
    fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getStorageParticipationStatus(): Boolean
    fun getAvailableStorageDevices(): List<StorageDeviceDto>
    fun setStorageAllocation(deviceId: String, 
    path: String,
    allocatedMB: Long, )
    fun getStorageAllocations(): List<StorageAllocationDto>
    fun enableDistributedStorage()
    fun disableDistributedStorage()
    fun isComputeLayerParticipating(): Boolean

    
    /**
     * Set the drop folder URI for broadcast file reception.
     * @param uri The content:// URI from Android's folder picker
     */
    fun setDropFolderUri(uri: String)
    
    /**
     * Get the stored drop folder URI.
     * @return The content:// URI, or null if not set
     */
    fun getDropFolderUri(): String?
    
    /**
     * Set the storage quota for mesh participation in bytes.
     * @param quotaBytes Maximum storage allocation in bytes
     */
    fun setStorageQuotaBytes(quotaBytes: Long)
    
    /**
     * Get the configured storage quota in bytes.
     * @return Quota in bytes (default: 100MB)
     */
    fun getStorageQuotaBytes(): Long

    // --- Broadcast Message+File Operations ---
    /**
     * Broadcast a message and/or file to all nodes in the mesh (suspend version)
     * 
     * Success results are reported via setOnBroadcastSent() handler.
     * Failures are reported via setOnBroadcastFailed() handler.
     * 
     * @param messageText Text message to broadcast (max 500 chars, can be empty if file provided)
     * @param filePath Absolute path to file to broadcast (can be empty if message provided)
     * @throws IllegalArgumentException if both messageText and filePath are empty
     * @throws IllegalArgumentException if message exceeds 500 characters
     * @throws IllegalStateException if drop folder not selected
     * @throws IllegalStateException if mesh is not running
     */
    suspend fun broadcastMessageAndFile(
        messageText: String = "",
        filePath: String = "",
        latitude: Double? = null,
        longitude: Double? = null
    )
    
    /**
     * Register a listener for received broadcasts
     * 
     * Listener is called on background thread when broadcast is fully received
     * and file has been written to Shared/ folder.
     * 
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     * 
     * @param listener Callback for received broadcasts
     */
    fun registerBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit)
    
    /**
     * Unregister a broadcast listener
     * 
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    fun unregisterBroadcastListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit)

    /**
     * Register handler for successful broadcast completion
     * Handler is invoked on background thread when broadcast is fully sent
     * 
     * @param handler Callback with broadcast result details
     */
    fun setOnBroadcastSent(handler: (com.ustadmobile.meshrabiya.api.model.BroadcastResultDto) -> Unit)

    /**
     * Register handler for broadcast failures
     * Handler is invoked on background thread when broadcast fails
     * 
     * @param handler Callback with failure details
     */
    fun setOnBroadcastFailed(handler: (broadcastId: String, error: Throwable) -> Unit)

    /**
     * Enable or disable the entire compute service (persistent, global).
     */
    fun setComputeLayerParticipatingEnabled(enabled: Boolean)

    // --- Drop Folder Management ---
    // fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
    // fun getDropFolder(): File?
    fun getDropFolderFiles(): List<File>
    fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit)

    // --- File Operations ---
    fun storeFile(file: File, recipients:List<RecipientEntryDto>) 
    suspend fun retrieveFile(fileId: String): ByteArray? 
    fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun getAllMeshFiles(): List<MeshFileDto>

    // --- Distributed Service Layer ---
    fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getAvailableServices(): List<String>
    fun getServiceParticipationStatus(serviceId: String): Boolean

    // --- Compute/Task Operations ---
    fun addTask(serviceId: String,requestParams: Map<String, Any>, recipients: List<RecipientEntryDto>): Any?
    // fun startTask(taskId: String, callback: (Result<Unit>) -> Unit)
    fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These methods reference scheduler types (ExecutionPlan, ComputeTask) that are unused in Phase 3-4
    // Phase 3-4 uses IntelligentDistributedComputeService.processTaskRequest() with direct node selection
    // If scheduler needed in future, uncomment these and restore ExecutionPlan/ComputeTask imports
    // fun getTaskStatus(taskId: String): ExecutionPlan?
    // fun getAllTasks(): List<ComputeTask>
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission

    // --- Event Registration ---
    fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)
    fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit)
    fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)
    fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // This callback uses ExecutionPlan type from unused scheduler infrastructure
    // fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit)
    
    fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
    fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)

    // --- Settings and State ---
    fun getSettings(): Map<String, Any>
    fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)

    // --- Service Bundle & Gateway Controls ---
    // fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit)
    // fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit)
    fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean)
    fun getMeshTrafficRouterStatus(): String

    // --- Event/Callback Integration ---
    fun setOnMeshStateChanged(handler: (newState: MeshStateDto) -> Unit)
    fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit)
    // fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit)
    // fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit)
    fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)
    
    /**
     * Section 9: Task status update callback
     * Invoked when a distributed compute task changes status.
     * 
     * @param handler Callback function receiving taskId and status string
     */
    fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit)

    /**
     * Returns whether the given TaskType is enabled for compute participation.
     */
    fun isTaskTypeEnabled(taskType: TaskTypeDto): Boolean

    /**
     * Sets enabled status for a TaskType (persistently).
     */
    fun setTaskTypeEnabled(taskType: TaskTypeDto, enabled: Boolean)

    /**
     * Returns a map of all TaskTypes and their enabled status.
     */
    fun getAllTaskTypeEnabled(): Map<TaskTypeDto, Boolean>
    // --- User Identity API ---
    /**
     * Returns current user info (userId, publicKey, nickname).
     */
    fun getUserInfo(): User

    /**
     * Sets the user's nickname (persistent).
     */
    fun setUserNickname(nickname: String)

    /**
     * Rotates the user's keypair and updates userId/publicKey.
     */
    fun rotateUserKey(): User

    // ========================================
    // WiFi Internet Connection API (WIFI_AP_CON)
    // ========================================

    /**
     * Connect to a non-mesh WiFi network while the mesh remains active.
     *
     * Requires AP+STA concurrency (hotspot mode, API 30+) or STA/STA concurrency
     * (Join Mesh mode, API 31+). Returns failure if hardware does not support the
     * required mode.
     *
     * @param ssid Target WiFi network SSID.
     * @param passphrase WPA2 passphrase. Pass empty string for open networks.
     * @return NonMeshWifiConnectionStateDto with status CONNECTED on success, FAILED on failure.
     */
    suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto

    /**
     * Returns true if this device is capable of hosting a Wi‑Fi hotspot / AP.
     * This check is performed once during initialization; callers may also watch
     * the `state` flow for live updates.
     */
    fun isApCapable(): Boolean

    /**
     * Returns true if this device supports concurrent AP+Station mode
     * (hotspot running while simultaneously connected as a WiFi client).
     * Requires API 30+ and hardware support (isStaApConcurrencySupported).
     * Distinct from [isApCapable] — a device may be AP-capable but NOT support concurrent AP+STA.
     */
    fun isApStaConcurrentCapable(): Boolean

    /**
     * Returns true if this device supports simultaneous dual-STA mode
     * (connected to two WiFi networks at the same time as a client).
     * Requires API 31+ and hardware support (isStaConcurrencyForLocalOnlyConnectionsSupported).
     * Distinct from both [isApCapable] and [isApStaConcurrentCapable].
     */
    fun isStaStaConcurrentCapable(): Boolean

    /**
     * Disconnect from the non-mesh internet WiFi.
     * Removes the WifiNetworkSuggestion and releases the internet Network object.
     * @return true if disconnection was performed, false if no connection was active.
     */
    suspend fun disconnectFromNonMeshWifi(): Boolean

    /**
     * Starts a local-only hotspot using the passphrase stored from the most recent joinMesh() QR scan.
     * This allows nearby devices to join this node's AP and reach the mesh (AP extension mode).
     * Only works reliably on API 33+; on older devices the OS assigns a random passphrase.
     */
    fun startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * Stops the mesh extender hotspot started via [startMeshExtenderHotspot].
     */
    fun stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * StateFlow emitting the current state of the mesh extender hotspot.
     */
    val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto>

    /**
     * StateFlow emitting true when the local mesh AP (LocalOnlyHotspot or WifiDirect)
     * is fully started. This is the authoritative trigger for showing the QR/join pane.
     * Independent of mesh network status (CONNECTING/CONNECTED) — a joining station
     * should never emit true here.
     */
    val meshApActiveFlow: StateFlow<Boolean>

    /**
     * Observe the current non-mesh WiFi connection state.
     * Emits [NonMeshWifiConnectionStateDto] updates as connection state changes.
     */
    fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto>

    /**
     * Scan for available WiFi networks.
     * Requires ACCESS_FINE_LOCATION permission.
     * @return List of discovered networks, ordered by signal strength descending.
     */
    suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto>

    /**
     * Returns true when the internet WiFi connection feature is currently available.
     *
     * Two paths to true:
     *   1. AP+STA mode: hotspot is running AND isStaApConcurrencySupported = true (API 30+)
     *   2. STA/STA mode: in Join Mesh AND isStaStaConcurrencySupported = true (API 31+)
     *
     * Returns false when mesh is not initialized, API < 30, or neither capability is present.
     */
    fun isInternetWifiFeatureAvailable(): Boolean

    /**
     * Returns true if the Android WiFi radio is currently enabled (WifiManager.isWifiEnabled).
     * Works on all SDK versions — the getter is not deprecated.
     * Used as a pre-flight gate before the Join Mesh flow.
     * Returns false if the mesh node is not yet initialized.
     */
    fun isWifiEnabled(): Boolean

    // === MESH PROXY APPS (Phase 2) ===

    /**
     * Persist the set of package names whose traffic this node will proxy through its
     * internet connection on behalf of remote mesh peers.
     * Stored via DataStore using [MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES].
     */
    suspend fun setMeshProxyApps(packageNames: Set<String>)

    /**
     * Return the currently persisted set of package names for mesh proxy.
     * Returns empty set if none configured.
     */
    suspend fun getMeshProxyApps(): Set<String>

    /**
     * Observe whether mesh proxy is currently active (i.e. the proxy VPN service is running
     * and at least one package is configured). Emits false when not active.
     */
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>

    /**
     * Emits true when BOTH conditions hold simultaneously:
     *   1. The local device does NOT have direct internet (nonMeshHasInternet == false), AND
     *   2. At least one CLEARNET_GATEWAY node is reachable in the mesh topology.
     * Used by [MeshProxyController] to decide when to activate mesh-proxy VPN mode.
     */
    fun getMeshInternetGatewayAvailableFlow(): StateFlow<Boolean>

    /**
     * The loopback TCP port on which [MeshLocalSocksProxy] is currently listening.
     * Returns 0 if the proxy server has not been started via [startMeshProxyServer].
     */
    fun getMeshProxySocksPort(): Int

    /** Start the local SOCKS5 mesh-proxy server. Idempotent. */
    fun startMeshProxyServer()

    /** Stop the local SOCKS5 mesh-proxy server. Idempotent. */
    fun stopMeshProxyServer()
}


