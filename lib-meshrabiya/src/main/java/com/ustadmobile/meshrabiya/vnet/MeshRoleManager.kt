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
import com.ustadmobile.meshrabiya.beta.ConnectivityMonitor
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import java.util.ArrayDeque

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
    private val virtualNode: VirtualNode,
    private val context: Context,
) {
    private val logger = BetaTestLogger()
    private val connectivityMonitor = ConnectivityMonitor(context)
    private val _currentRole = MutableStateFlow(NodeRole.MESH_NODE)
    val currentRole: StateFlow<NodeRole> = _currentRole

    var chokePointFlag: Boolean = false
    var centralityScore: Float = 0f
    var userAllowsTorProxy: Boolean = false // Should be set based on user config

    init {
        connectivityMonitor.startMonitoring()
    }

    fun calculateFitnessScore(): FitnessScore {
        val wifiState = (virtualNode as? HasNodeState)?.currentNodeState?.wifiState ?: MeshrabiyaWifiState()
        val bluetoothState = (virtualNode as? HasNodeState)?.currentNodeState?.bluetoothState ?: MeshrabiyaBluetoothState()
        val isConnected = connectivityMonitor.isConnected.value

        val signalStrength = when {
            wifiState.connectConfig != null -> 100
            bluetoothState.deviceName != null -> 50
            else -> 0
        }

        val batteryLevel = 0.5f // TODO: Implement battery level monitoring
        val clientCount = virtualNode.neighbors().size

        return FitnessScore(
            signalStrength = signalStrength,
            batteryLevel = batteryLevel,
            clientCount = clientCount,
        )
    }

    fun updateRole() {
        val score = calculateFitnessScore()
        val centrality = calculateCentralityScore()
        val combinedScore = score.signalStrength * 0.5f + centrality * 0.5f
        val newRole = when {
            userAllowsTorProxy || chokePointFlag -> NodeRole.MESH_NODE
            combinedScore > 80 -> NodeRole.MESH_NODE
            combinedScore > 50 -> NodeRole.BRIDGE
            else -> NodeRole.CLIENT
        }
        if (newRole != currentRole.value) {
            logger.log(LogLevel.INFO, "Role changed from ${currentRole.value} to $newRole")
            _currentRole.value = newRole
        }
    }

    fun close() {
        connectivityMonitor.stopMonitoring()
    }
}

data class FitnessScore(
    val signalStrength: Int,
    val batteryLevel: Float,
    val clientCount: Int,
)

fun MeshRoleManager.updateNeighborFitnessInfo(neighborId: String, fitnessScore: Int, nodeRole: Byte) {
    // Implementation needed
}

fun MeshRoleManager.updateNeighborSignalStrength(neighborId: String, rssi: Int) {
    // Implementation needed
}

fun MeshRoleManager.calculateCentralityScore(): Float {
    val topology = (virtualNode.originatingMessageManager as? com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager)?.getTopologyMap() ?: return 0f
    val myAddr = virtualNode.addressAsInt
    val minChokePointNeighbors = 2 // or make configurable

    // Choke point detection
    val chokePointFlag = topology.any { it.value.size <= minChokePointNeighbors }

    // BFS for hops and centrality
    val visited = mutableSetOf<Int>()
    val queue = ArrayDeque<Pair<Int, Int>>() // Pair<address, hops>
    queue.add(myAddr to 0)
    visited.add(myAddr)
    var totalHops = 0
    var maxHops = 0
    var reachable = 0
    while (queue.isNotEmpty()) {
        val (current, hops) = queue.removeFirst()
        if (hops > 0) {
            totalHops += hops
            maxHops = maxOf(maxHops, hops)
            reachable++
        }
        for (neighbor in topology[current] ?: emptySet()) {
            if (neighbor !in visited) {
                visited.add(neighbor)
                queue.add(neighbor to hops + 1)
            }
        }
    }
    val avgHops = if (reachable > 0) totalHops.toFloat() / reachable else 0f
    val degree = topology[myAddr]?.size ?: 0
    val centralityScore = degree + (if (avgHops > 0) 1f / avgHops else 0f)

    // Optionally, store chokePointFlag and centralityScore as properties
    this.chokePointFlag = chokePointFlag
    this.centralityScore = centralityScore

    return centralityScore
}

fun MeshRoleManager.gossipFitnessScore() {
    // Implementation needed
}

fun MeshRoleManager.shouldProvideHotspot(): Boolean {
    // Implementation needed
    return false
}

fun MeshRoleManager.setEnabled(enabled: Boolean) {
    // Implementation needed
}

fun MeshRoleManager.getNeighborFitnessInfo(): Map<String, Pair<Int, Byte>> {
    // Implementation needed
    return emptyMap()
}

// TODO: Add methods for updating neighbor scores, handling role transitions, etc. 