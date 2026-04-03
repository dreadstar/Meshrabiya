package com.ustadmobile.meshrabiya.api.model
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import android.util.Base64
import com.ustadmobile.meshrabiya.vnet.LocalNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.flow.Flow
import com.ustadmobile.meshrabiya.vnet.MeshFile
import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.InternetWifiNetworkState
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNode.LastOriginatorMessage
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
// DTOs and conversion functions for MeshState, LocalNodeState, NetworkInfo, NodeInfo, GatewayPreference, VirtualPacket, ApiResult, MeshFile
// Recursively includes DTOs for all custom property types and enums

// MeshState DTO
enum class MeshStateDto {
    INITIALIZING, CONNECTING, CONNECTED, DISCONNECTED, ERROR, UNKNOWN;
}

@kotlinx.serialization.Serializable
data class NetworkOverviewMetricsDto(
    val uploadBps: Long,
    val downloadBps: Long,
    val activeNodeCount: Int
)

fun MeshState.toDto() = MeshStateDto.valueOf(this.name)
fun MeshStateDto.toInternal() = MeshState.valueOf(this.name)

// GatewayPreference DTO
// enum class GatewayPreferenceDto {
//     TOR_ONLY, CLEARNET_ONLY, TOR_PREFERRED, CLEARNET_PREFERRED, AUTO;
// }

// fun GatewayPreference.toDto() = GatewayPreferenceDto.valueOf(this.name)
// fun GatewayPreferenceDto.toInternal() = GatewayPreference.valueOf(this.name)



// MeshFile DTO
 data class MeshFileDto(
    val fileId: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val owner: RecipientEntryDto,
    val recipients: List<RecipientEntryDto>,
    val createdAt: Long,
    val relativePath: String
)

fun MeshFile.toDto() = MeshFileDto(
    fileId, fileName, path, sizeBytes, owner.toDto(), recipients.map { it.toDto() }, createdAt, relativePath
)
fun MeshFileDto.toInternal() = MeshFile(
    fileId, fileName, path, sizeBytes, owner.toInternal(), recipients.map { it.toInternal() }, createdAt, relativePath
)

// NetworkInfo DTO
data class NetworkInfoDto(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val connectedPeers: Int,
    val isConnected: Boolean,
    /** True when the Orbot VPN tunnel is active and has confirmed internet access. */
    val vpnHasInternet: Boolean = false,
    /**
     * True when the VPN tunnel's underlying transport is WiFi (determined in TorStatusMonitor).
     * When true on a single-radio device the mesh cannot run simultaneously.
     */
    val vpnOverWifi: Boolean = false,
    val torGateways: Int,
    val clearnetGateways: Int,
    val meshProxyActive: Boolean = false,
)

fun NetworkInfo.toDto(
    vpnHasInternet: Boolean = false,
    vpnOverWifi: Boolean = false,
    meshProxyActive: Boolean = false,
) = NetworkInfoDto(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    vpnHasInternet,
    vpnOverWifi,
    torGateways,
    clearnetGateways,
    meshProxyActive,
)

fun NetworkInfoDto.toInternal() = NetworkInfo(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    torGateways,
    clearnetGateways
)

// NodeInfo DTO
 data class NodeInfoDto(
    val nodeId: String,
    val displayName: String,
    val isOnline: Boolean,
    val lastSeen: Long,
    val capabilities: List<String>
)

fun NodeInfo.toDto() = NodeInfoDto(
    nodeId, displayName, isOnline, lastSeen, capabilities
)
fun NodeInfoDto.toInternal() = NodeInfo(
    nodeId, displayName, isOnline, lastSeen, capabilities
)

/**
 * Hotspot information data transfer object
 * Contains network credentials and configuration for joining mesh
 */
@Serializable
data class HotspotInfoDto(
    /**
     * Network SSID (hotspot name)
     * Format depends on Android version:
     * - Android 13+: "meshr-<virtualaddr_hex>" (e.g., "meshr-a9fe2d8e")
     * - Android 8-12: "AndroidShare_XXXX" (random)
     */
    val ssid: String,
    
    /**
     * Network password (WPA2-PSK passphrase)
     * - Android 13+: "meshtest12" (hardcoded, shared by all devices)
     * - Android 8-12: Random Android-generated password
     */
    val password: String,
    
    /**
     * Frequency band (2.4GHz, 5GHz, or unknown)
     */
    val band: String,
    
    /**
     * Virtual address of hotspot owner (32-bit integer)
     * Used for mesh routing and identification
     */
    val nodeAddress: Int,
    
    /**
     * Optional: BSSID (MAC address) for sticky connection
     * Helps device reconnect to same hotspot even if SSID is duplicated
     */
    val bssid: String? = null,
    
    /**
     * Hotspot type: LOCAL_ONLY or WIFI_DIRECT
     */
    val hotspotType: String = "LOCAL_ONLY",
    
    /**
     * UDP port number for mesh communication
     * This is the actual port the VirtualNode's socket is listening on
     */
    val port: Int,
)

// ApiResult DTO
sealed class ApiResultDto {
    object Success : ApiResultDto()
    data class Failure(val error: String) : ApiResultDto()
}

fun ApiResult.toDto(): ApiResultDto = when(this) {
    is ApiResult.Success -> ApiResultDto.Success
    is ApiResult.Failure -> ApiResultDto.Failure(error.toString())
}
fun ApiResultDto.toInternal(): ApiResult = when(this) {
    is ApiResultDto.Success -> ApiResult.Success
    is ApiResultDto.Failure -> ApiResult.Failure(Exception(error))
}

// LocalNodeState DTO (partial, nested DTOs required)
data class LocalNodeStateDto(
    val address: Int,
    val wifiState: MeshrabiyaWifiStateDto,
    val bluetoothState: MeshrabiyaBluetoothStateDto,
    val connectUri: String?,
    val originatorMessages: Map<Int, LastOriginatorMessageDto>,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L
)

fun LocalNodeState.toDto(): LocalNodeStateDto = LocalNodeStateDto(
    address = address,
    wifiState = wifiState.toDto(),
    bluetoothState = bluetoothState.toDto(),
    connectUri = connectUri,
    originatorMessages = originatorMessages.mapValues { it.value.toDto() },
    uploadBytes = uploadBytes,
    downloadBytes = downloadBytes
)

fun LocalNodeStateDto.toInternal(): LocalNodeState = LocalNodeState(
    address = address,
    wifiState = wifiState.toInternal(),
    bluetoothState = bluetoothState.toInternal(),
    connectUri = connectUri,
    originatorMessages = originatorMessages.mapValues { it.value.toInternal() },
    uploadBytes = uploadBytes,
    downloadBytes = downloadBytes
)

// MeshrabiyaWifiState DTO
data class MeshrabiyaWifiStateDto(
    val wifiRole: String,
    val wifiDirectState: WifiDirectStateDto,
    val wifiStationState: WifiStationStateDto,
    val localOnlyHotspotState: LocalOnlyHotspotStateDto,
    val errorCode: Int,
    val concurrentApStationSupported: Boolean,
    val apCapable: Boolean                 
)

// MeshrabiyaBluetoothState DTO
data class MeshrabiyaBluetoothStateDto(
    val deviceName: String?
)

// LastOriginatorMessage DTO (partial, nested types required)
 data class LastOriginatorMessageDto(
    val originatorMessage: MmcpOriginatorMessageDto,
    val timeReceived: Long,
    val lastHopAddr: Int,
    val hopCount: Byte,
    val lastHopRealInetAddr: String?,
    val lastHopRealPort: Int,
    val neighborAddr: String?
)

/// Moved from Api.kt
data class DropFolderItemDto(
    val itemRelativePath: String,
    val isFolder: Boolean,
    // val children: List<DropFolderItemDto> = emptyList(),
    val trigger: StoreFileTriggerDto? = null
)





@Serializable
data class RecipientEntryDto(
    val publicKey: String,
    val recipientType: RecipientTypeDto, // Use String for enum in DTO
    val recipientId: String,
    val expiresAt: Long? = null
)

@Serializable
enum class RecipientTypeDto {
    /**
     * Long-lived user/node keypairs.
     * Used for persistent node identities and user accounts.
     * Never expires automatically.
     */
    USER,
    
    /**
     * Ephemeral task keypairs.
     * Generated per-task, expires after task completion or timeout.
     * Provides task-level data isolation from compute node operators.
     */
    TASK
}
fun RecipientType.toDto() = RecipientTypeDto.valueOf(this.name)
fun RecipientTypeDto.toInternal() = RecipientType.valueOf(this.name)

@Serializable
data class StoreFileTriggerDto(
    val id: Int,
    val subPath: String, 
    val recipients: List<RecipientEntryDto>,
    
)

data class StorageAllocationDto(
    val deviceId: String,
    val path: String,
    val allocatedMB: Long,
    
    // val enabled: Boolean
)

enum class StorageDeviceTypeDto{
    INTERNAL,
    EXTERNAL,
    USB
}

data class StorageDeviceDto(
    val id: String,
    val name: String,
    val path: String,
    val availableSpaceGB: Float,
    val totalSpaceGB: Float,
    val type: StorageDeviceTypeDto
)
fun StorageDeviceDto.getFormattedAvailableSpace(): String {
    return when {
        availableSpaceGB >= 1024 -> "${(availableSpaceGB / 1024).toInt()} TB"
        availableSpaceGB >= 1 -> "${availableSpaceGB.toInt()} GB"
        else -> "${(availableSpaceGB * 1024).toInt()} MB"
    }
}

// Moved from ApiImpl.kt to here for conversion functions
// In DropFolderItem.kt (internal model)
fun DropFolderItem.toDto(): DropFolderItemDto = DropFolderItemDto(
    itemRelativePath = itemRelativePath,
    isFolder = isFolder,
    trigger = trigger?.toDto()
)

fun DropFolderItemDto.toInternal(): DropFolderItem = DropFolderItem(
    itemRelativePath = itemRelativePath,
    isFolder = isFolder,
    trigger = trigger?.toInternal()
)

// In StoreFileTrigger.kt
fun StoreFileTrigger.toDto(): StoreFileTriggerDto = StoreFileTriggerDto(
    id=id,
    subPath = subPath,
    recipients = recipients.map { it.toDto() },

)

fun StoreFileTriggerDto.toInternal(): StoreFileTrigger = StoreFileTrigger(
    id=id,
    subPath = subPath,
    recipients = recipients.map { it.toInternal() },
    
)

// In RecipientEntry.kt
fun RecipientEntry.toDto(): RecipientEntryDto = RecipientEntryDto(
    publicKey = publicKey,
    recipientType = recipientType.toDto(),
    recipientId = recipientId,
    expiresAt = expiresAt
)

fun RecipientEntryDto.toInternal(): RecipientEntry = RecipientEntry(
    publicKey = publicKey,
    recipientType = RecipientType.valueOf(recipientType.name),
    recipientId = recipientId,
    expiresAt = expiresAt
)

fun StorageAllocation.toDto(): StorageAllocationDto = StorageAllocationDto(
    path = path,
    allocatedMB = allocatedMB,
    deviceId = deviceId
    // enabled = enabled
)

fun StorageAllocationDto.toInternal(): StorageAllocation = StorageAllocation(
    path = path,
    allocatedMB = allocatedMB,
    deviceId = deviceId
    // enabled = enabled
)

fun StorageDeviceTypeDto.toInternal(): StorageDeviceType =
    StorageDeviceType.valueOf(this.name)

fun StorageDeviceDto.toInternal(): StorageDevice =
StorageDevice(
    id = id,
    name = name,
    path = path,
    availableSpaceGB = availableSpaceGB,
    totalSpaceGB = totalSpaceGB,
    type = type.toInternal()
)

fun StorageDeviceType.toDto(): StorageDeviceTypeDto =
    StorageDeviceTypeDto.valueOf(this.name)

fun StorageDevice.toDto(): StorageDeviceDto =
StorageDeviceDto(
    id = id,
    name = name,
    path = path,
    availableSpaceGB = availableSpaceGB,
    totalSpaceGB = totalSpaceGB,
    type = type.toDto()
)

fun MeshrabiyaWifiState.toDto() = MeshrabiyaWifiStateDto(
    wifiRole = wifiRole.name,
    wifiDirectState = wifiDirectState.toDto(),
    wifiStationState = wifiStationState.toDto(),
    localOnlyHotspotState = localOnlyHotspotState.toDto(),
    errorCode = errorCode,
    concurrentApStationSupported = concurrentApStationSupported,
    apCapable = apCapable                   // propagate flag
)

fun MeshrabiyaWifiStateDto.toInternal() = MeshrabiyaWifiState(
    wifiRole = com.ustadmobile.meshrabiya.vnet.WifiRole.valueOf(wifiRole),
    wifiDirectState = wifiDirectState.toInternal(),
    wifiStationState = wifiStationState.toInternal(),
    localOnlyHotspotState = localOnlyHotspotState.toInternal(),
    errorCode = errorCode,
    concurrentApStationSupported = concurrentApStationSupported,
    apCapable = apCapable                   // round‑trip in converter
)

fun MeshrabiyaBluetoothState.toDto() = MeshrabiyaBluetoothStateDto(
    deviceName = deviceName
)

fun MeshrabiyaBluetoothStateDto.toInternal() = MeshrabiyaBluetoothState(
    deviceName = deviceName
)

fun VirtualNode.LastOriginatorMessage.toDto() = LastOriginatorMessageDto(
    originatorMessage = originatorMessage.toDto(),
    timeReceived = timeReceived,
    lastHopAddr = lastHopAddr,
    hopCount = hopCount,
    lastHopRealInetAddr = lastHopRealInetAddr.hostAddress,
    lastHopRealPort = lastHopRealPort,
    neighborAddr = neighborAddr.hostAddress
)

fun LastOriginatorMessageDto.toInternal(): VirtualNode.LastOriginatorMessage {
    throw UnsupportedOperationException("Cannot convert LastOriginatorMessageDto back to VirtualNode.LastOriginatorMessage: receivedFromSocket cannot be reconstructed from DTO")
}

// --- WifiDirectState DTO ---
data class WifiDirectStateDto(
    val hotspotStatus: String,
    val error: Int,
    val config: WifiConnectConfigDto?
)

fun WifiDirectState.toDto() = WifiDirectStateDto(
    hotspotStatus = hotspotStatus.name,
    error = error,
    config = config?.toDto()
)

fun WifiDirectStateDto.toInternal() = WifiDirectState(
    hotspotStatus = com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus.valueOf(hotspotStatus),
    error = error,
    config = config?.toInternal()
)

// --- WifiStationState DTO ---
data class WifiStationStateDto(
    val status: String,
    val config: WifiConnectConfigDto?,
    val stationBoundSocketsPort: Int
)

fun WifiStationState.toDto() = WifiStationStateDto(
    status = status.name,
    config = config?.toDto(),
    stationBoundSocketsPort = stationBoundSocketsPort
)

fun WifiStationStateDto.toInternal() = WifiStationState(
    status = com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.valueOf(status),
    config = config?.toInternal(),
    stationBoundSocketsPort = stationBoundSocketsPort
)

// --- LocalOnlyHotspotState DTO ---
data class LocalOnlyHotspotStateDto(
    val status: String,
    val config: WifiConnectConfigDto?,
    val error: Int
)

fun LocalOnlyHotspotState.toDto() = LocalOnlyHotspotStateDto(
    status = status.name,
    config = config?.toDto(),
    error = error
)

fun LocalOnlyHotspotStateDto.toInternal() = LocalOnlyHotspotState(
    status = com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus.valueOf(status),
    config = config?.toInternal(),
    error = error
)

// --- WifiConnectConfig DTO ---
data class WifiConnectConfigDto(
    val nodeVirtualAddr: Int,
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val hotspotType: String,
    val persistenceType: String,
    val band: String,
    val bssid: String?
)

fun WifiConnectConfig.toDto() = WifiConnectConfigDto(
    nodeVirtualAddr = nodeVirtualAddr,
    ssid = ssid,
    passphrase = passphrase,
    port = port,
    hotspotType = hotspotType.name,
    persistenceType = persistenceType.name,
    band = band.name,
    bssid = bssid
)

fun WifiConnectConfigDto.toInternal() = WifiConnectConfig(
    nodeVirtualAddr = nodeVirtualAddr,
    ssid = ssid,
    passphrase = passphrase,
    linkLocalAddr = null, // Not serializable as string here
    port = port,
    hotspotType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotType.valueOf(hotspotType),
    persistenceType = com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType.valueOf(persistenceType),
    band = com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand.valueOf(band),
    bssid = bssid
)


// --- MeshRole DTO ---
enum class MeshRoleDto {
    MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, MESH_HUB, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
}

fun MeshRole.toDto() = MeshRoleDto.valueOf(this.name)
fun MeshRoleDto.toInternal() = MeshRole.valueOf(this.name)

// --- MmcpOriginatorMessage DTO ---
data class MmcpOriginatorMessageDto(
    val messageId: Int,
    val sentTime: Long,
    val pingTimeSum: Short,
    val connectConfig: String?, // TODO: Replace with platform-agnostic DTO if needed
    val neighbors: List<Int>,
    val centralityScore: Float,
    val fitnessScore: Float,
    val meshRoles: Set<MeshRoleDto>
)

fun MmcpOriginatorMessage.toDto() = MmcpOriginatorMessageDto(
    messageId = this.messageId,
    sentTime = this.sentTime,
    pingTimeSum = this.pingTimeSum,
    connectConfig = this.connectConfig?.let { Base64.encodeToString(it.toBytes(), Base64.NO_WRAP) },
    neighbors = this.neighbors,
    centralityScore = this.centralityScore,
    fitnessScore = this.fitnessScore,
    meshRoles = this.meshRoles.map { it.toDto() }.toSet()
)

fun MmcpOriginatorMessageDto.toInternal(): MmcpOriginatorMessage =
    MmcpOriginatorMessage(
        messageId = messageId,
        sentTime = sentTime,
        pingTimeSum = pingTimeSum,
        connectConfig = connectConfig?.let { 
            val bytes = Base64.decode(it, Base64.NO_WRAP)
            com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig.fromBytes(bytes, 0)
        },
        neighbors = neighbors,
        centralityScore = centralityScore,
        fitnessScore = fitnessScore,
        meshRoles = meshRoles.map { it.toInternal() }.toSet()
    )

@Serializable
enum class TaskTypeDto {
    /**
     * Python scripts (requires Chaquopy runtime)
     * - Executor: PythonExecutor
     * - Sandbox: Python-specific syscall whitelist
     * - Bundle format: .py files or Python wheel
     */
    PYTHON,

    /**
     * Compiled Java bytecode (requires JVM)
     * - Executor: JVMExecutor
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JAVA,

    /**
     * JVM languages (Kotlin, Scala, Groovy)
     * - Executor: JVMExecutor (same as JAVA)
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JVM,

    /**
     * JavaScript code (requires Node.js or J2V8)
     * - Executor: JSExecutor
     * - Sandbox: JS-specific syscall whitelist
     * - Bundle format: .js files or npm package
     */
    JAVASCRIPT,

    /**
     * Native ML models (TFLite, ML Kit)
     * - Executor: MLNativeExecutor
     * - Sandbox: Native code restrictions
     * - Bundle format: .tflite, .tflite.json, ML Kit model files
     */
    ML_NATIVE,

    /**
     * Multi-step workflow orchestration
     * - Executor: WorkflowExecutor
     * - Sandbox: Workflow-specific (orchestrates other tasks)
     * - Bundle format: JSON/YAML workflow definition
     */
    WORKFLOW;

   
}

data class NeighborInfoDto(
    val nodeId: Int,
    val message: LastOriginatorMessageDto
)

fun TaskType.toDto(): TaskTypeDto = TaskTypeDto.valueOf(this.name)
fun TaskTypeDto.toInternal(): TaskType = TaskType.valueOf(this.name)

/**
 * Result of broadcast send operation
 */
data class BroadcastResultDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val totalChunks: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val successNodeIds: List<Int>,  // Nodes that acknowledged receipt
    val failedNodeIds: List<Int>,   // Nodes that failed or timed out
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Notification of received broadcast
 */
data class BroadcastReceivedDto(
    val broadcastId: String,
    val messageText: String,
    val fileId: String,
    val fileName: String,
    val filePath: String,  // Path in Shared/ folder
    val senderNodeId: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val hasError: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Progress update during broadcast send
 */
data class BroadcastProgressDto(
    val broadcastId: String,
    val chunksSent: Int,
    val totalChunks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long
)

// data class NetworkOverviewMetricsDto(
//     val uploadRateBytesPerSec: Long,
//     val downloadRateBytesPerSec: Long,
//     val activeNodeCount: Int
// )

// ========================================
// WiFi Internet Connection DTOs
// Added for WIFI_AP_CON feature (11.4)
// ========================================

/**
 * Represents a discovered WiFi network available for internet connection.
 * Returned by MeshrabiyaApi.scanAvailableWifiNetworks().
 */
data class NonMeshWifiNetworkDto(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val isSecured: Boolean,
)

/**
 * Request to connect to a non-mesh WiFi network.
 * Used internally; the API takes ssid + passphrase directly.
 */
data class WifiConnectionRequestDto(
    val ssid: String,
    val passphrase: String,
)

/**
 * Represents the current state of the mesh extender (AP extension) hotspot.
 * INACTIVE: Not started.
 * STARTING: Hotspot start in progress.
 * ACTIVE: Hotspot is running and accessible.
 * STOPPING: Hotspot stop in progress.
 */
enum class MeshExtenderHotspotStateDto {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING
}

/**
 * Snapshot of the Orbot VPN state as seen by Meshrabiya.
 * Pushed by the app layer via MeshrabiyaApi.notifyVpnStateChanged().
 */
data class VpnStateDto(
    /** True when Orbot VPN tunnel is fully active ("ON"). */
    val active: Boolean,
    /**
     * True when the VPN tunnel's underlying transport is WiFi (not cellular).
     * Determined by NetworkCapabilities.hasTransport(TRANSPORT_VPN) &&
     * hasTransport(TRANSPORT_WIFI) on the active network.
     * When true the mesh cannot use the WiFi radio simultaneously on single-radio devices.
     */
    val vpnOverWifi: Boolean = false,
    /** SOCKS port Orbot is listening on, or null if not active. */
    val socksPort: Int? = null,
) {
    companion object {
        val INACTIVE = VpnStateDto(active = false)
    }
}