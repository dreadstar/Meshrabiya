package com.ustadmobile.meshrabiya.vnet

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val connectivityMonitor: ConnectivityMonitor? = null // Placeholder for future integration
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

    fun updateNeighborFitnessInfo(neighborId: String, fitnessScore: Int, nodeRole: Byte) {
        neighborFitnessInfo[neighborId] = Pair(fitnessScore, nodeRole)
    }

    fun calculateFitnessScore(): NodeFitnessScore {
        // TODO: Implement actual metrics collection
        val score = NodeFitnessScore(
            nodeId = virtualNode.addressAsInt.toString(),
            internetAccess = false, // Placeholder
            signalStrength = 0, // Placeholder
            batteryLevel = 1.0f, // Placeholder
            cpuUsage = 0.0f, // Placeholder
            clientCount = 0, // Placeholder
            timestamp = System.currentTimeMillis(),
            location = null // Placeholder
        )
        _fitnessScore.value = score
        return score
    }

    fun gossipFitnessScore() {
        // TODO: Implement gossip protocol for fitness scores
    }

    fun shouldProvideHotspot(): Boolean {
        // Example: Use neighbor info for load balancing or bridge selection
        val myScore = _fitnessScore.value?.fitnessScore ?: 0
        val bestNeighbor = neighborFitnessInfo.maxByOrNull { it.value.first }
        // Promote to hotspot if my score is higher than all neighbors, or if no mesh node nearby
        val shouldPromote = bestNeighbor == null || myScore > bestNeighbor.value.first
        if (!advisoryMode && shouldPromote) {
            _currentRole.value = NodeRole.MESH_NODE
        } else if (!advisoryMode) {
            _currentRole.value = NodeRole.CLIENT
        }
        return shouldPromote
    }

    fun setEnabled(enabled: Boolean) {
        advisoryMode = !enabled
    }

    // Expose neighbor info for diagnostics or advanced logic
    fun getNeighborFitnessInfo(): Map<String, Pair<Int, Byte>> = neighborFitnessInfo.toMap()

    // TODO: Add methods for updating neighbor scores, handling role transitions, etc.
} 