package com.ustadmobile.meshrabiya.vnet

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.app.ActivityManager
import android.os.Build
import android.content.Context
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * Enum representing the possible roles a node can take in the mesh.
 */
enum class NodeRole {
    MESH_NODE,
    CLIENT,
    BRIDGE
}

/**
 * Data class representing the fitness score of a node for mesh role assignment.
 */
data class NodeFitnessScore(
    val nodeId: String,
    val internetAccess: Boolean,
    val signalStrength: Int,
    val batteryLevel: Float,
    val cpuUsage: Float,
    val clientCount: Int,
    val timestamp: Long,
    val location: Location? = null
)

/**
 * Manager responsible for calculating fitness, gossiping scores, and deciding mesh roles.
 */
class MeshRoleManager(
    private val virtualNode: AndroidVirtualNode,
    private val connectivityMonitor: ConnectivityMonitor? = null, // Placeholder for future integration
    private val betaLogger: BetaTestLogger? = null
) {
    // Advisory mode: only logs decisions, does not act
    var advisoryMode: Boolean = true

    // Current fitness score
    private val _fitnessScore = MutableStateFlow<NodeFitnessScore?>(null)
    val fitnessScore: StateFlow<NodeFitnessScore?> = _fitnessScore

    // Current role
    private val _currentRole = MutableStateFlow(NodeRole.CLIENT)
    val currentRole: StateFlow<NodeRole> = _currentRole

    // Store neighbor fitness/role info
    private val neighborFitnessInfo: MutableMap<String, Pair<Int, Byte>> = mutableMapOf()
    // Store neighbor RSSI values for centrality
    private val neighborSignalStrengths: MutableMap<String, Int> = mutableMapOf()

    private fun logBeta(level: LogLevel, message: String, throwable: Throwable? = null) {
        betaLogger?.log(level, message, throwable)
    }

    fun updateNeighborFitnessInfo(neighborId: String, fitnessScore: Int, nodeRole: Byte) {
        neighborFitnessInfo[neighborId] = Pair(fitnessScore, nodeRole)
        logBeta(LogLevel.DEBUG, "Updated neighbor fitness info: $neighborId, fitnessScore=$fitnessScore, nodeRole=$nodeRole")
    }

    fun updateNeighborSignalStrength(neighborId: String, rssi: Int) {
        neighborSignalStrengths[neighborId] = rssi
        logBeta(LogLevel.DEBUG, "Updated neighbor signal strength: $neighborId, rssi=$rssi")
    }

    fun calculateCentralityScore(): Float {
        val neighborCount = neighborSignalStrengths.size
        val avgSignal = if (neighborCount > 0) neighborSignalStrengths.values.average().toFloat() else 0f
        val score = neighborCount * 2f + avgSignal / 10f
        logBeta(LogLevel.DEBUG, "Calculated centrality score: $score (neighborCount=$neighborCount, avgSignal=$avgSignal)")
        return score
    }

    fun calculateFitnessScore(): NodeFitnessScore {
        val context = virtualNode.appContext
        // Internet connectivity
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val internetAccess = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // Signal strength (RSSI)
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val signalStrength = wifiInfo?.rssi ?: 0

        // Battery level
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel: Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f
        } else {
            1.0f // fallback
        }

        // CPU usage (approximate, as Android restricts per-app CPU usage)
        val cpuUsage: Float = try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningAppProcesses = activityManager.runningAppProcesses
            // This is a placeholder; real CPU usage would require native code or /proc parsing
            runningAppProcesses?.size?.toFloat() ?: 0f
        } catch (e: Exception) { 0f }

        // Client count (number of connected devices to hotspot, if applicable)
        val clientCount: Int = try {
            // Only available on some Android versions and devices
            val method = wifiManager.javaClass.getMethod("getClientList")
            val clients = method.invoke(wifiManager) as? List<*>
            clients?.size ?: 0
        } catch (e: Exception) { 0 }

        // Centrality score
        val centralityScore = calculateCentralityScore()

        val score = NodeFitnessScore(
            nodeId = virtualNode.addressAsInt.toString(),
            internetAccess = internetAccess,
            signalStrength = signalStrength,
            batteryLevel = batteryLevel,
            cpuUsage = cpuUsage,
            clientCount = clientCount,
            timestamp = System.currentTimeMillis(),
            location = null // Placeholder, can be added if location permission is granted
        )
        _fitnessScore.value = score
        logBeta(LogLevel.INFO, "Calculated fitness score: $score, centralityScore=$centralityScore")
        return score
    }

    fun gossipFitnessScore() {
        // TODO: Implement gossip protocol for fitness scores
        //       When implementing, include multi-hop neighbor info (e.g., neighbor counts, centrality) for richer mesh awareness
    }

    fun shouldProvideHotspot(): Boolean {
        // Local centrality and neighbor count
        val myCentrality = calculateCentralityScore()
        val myNeighborCount = neighborSignalStrengths.size

        // Gather multi-hop neighbor info from OriginatingMessageManager
        val omm = (virtualNode as? AndroidVirtualNode)?.originatingMessageManager
        val multiHopCentralities = omm?.let {
            // neighborCentralityInfo is a private field, so use reflection if needed
            try {
                val field = it.javaClass.getDeclaredField("neighborCentralityInfo")
                field.isAccessible = true
                (field.get(it) as? Map<*, *>)?.values?.mapNotNull { v -> v as? Float } ?: emptyList()
            } catch (e: Exception) { emptyList<Float>() }
        } ?: emptyList()

        val multiHopNeighborCounts = omm?.state?.value?.values?.mapNotNull {
            try {
                val msg = it.originatorMessage
                msg.neighborCount
            } catch (e: Exception) { null }
        } ?: emptyList()

        val bestCentrality = multiHopCentralities.maxOrNull() ?: 0f
        val bestNeighborCount = multiHopNeighborCounts.maxOrNull() ?: 0

        // Promote to hotspot if this node is more central and has at least as many neighbors as any known neighbor
        val shouldPromote = (myCentrality > bestCentrality) && (myNeighborCount >= bestNeighborCount)
        if (!advisoryMode && shouldPromote) {
            _currentRole.value = NodeRole.MESH_NODE
            logBeta(LogLevel.INFO, "Promoted to MESH_NODE role")
        } else if (!advisoryMode) {
            _currentRole.value = NodeRole.CLIENT
            logBeta(LogLevel.INFO, "Demoted to CLIENT role")
        }
        return shouldPromote
    }

    fun setEnabled(enabled: Boolean) {
        advisoryMode = !enabled
        logBeta(LogLevel.INFO, "MeshRoleManager ${if (enabled) "enabled" else "disabled"}")
    }

    // Expose neighbor info for diagnostics or advanced logic
    fun getNeighborFitnessInfo(): Map<String, Pair<Int, Byte>> = neighborFitnessInfo.toMap()

    // TODO: Add methods for updating neighbor scores, handling role transitions, etc.
} 