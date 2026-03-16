package com.ustadmobile.meshrabiya.vnet

/**
 * Topology information for a single mesh node, extracted from MmcpOriginatorMessage.
 * Used for gateway selection and mesh intelligence.
 * 
 * Stores ALL role types:
 * - Gateway roles (TOR/CLEARNET/I2P) for routing decisions
 * - Intelligence roles (STORAGE/COMPUTE/MESH_ROUTER) for future optimization
 * 
 * @param nodeAddress Virtual address of the mesh node
 * @param neighbors Set of virtual addresses of direct neighbors
 * @param meshRoles Set of all roles this node offers (ALL 7 types)
 * @param centralityScore BFS centrality score (0.0-1.0) indicating network position
 * @param fitnessScore Hardware fitness score (0.0-1.0) indicating capability
 * @param lastSeen Timestamp when this node was last seen (originator message received)
 * @param pingTime Round-trip latency to reach this node (milliseconds)
 */
data class NodeTopologyInfo(
    val nodeAddress: Int,
    val neighbors: Set<Int>,
    val meshRoles: Set<MeshRole>,  // ALL 7 types stored
    val centralityScore: Float,
    val fitnessScore: Float,
    val lastSeen: Long = System.currentTimeMillis(),
    val pingTime: Short = 0,  // Latency to reach this node (ms)
    /** RSSI of non-mesh WiFi to internet AP, dBm. 0 = unknown. */
    val internetSignalStrengthDbm: Int = 0,
    /** Link speed of non-mesh WiFi, Mbps. 0 = unknown. */
    val internetLinkSpeedMbps: Int = 0,
) {
    /**
     * Check if node offers a specific role (gateway or intelligence)
     * @param role The role to check for
     * @return true if this node has the specified role
     */
    fun hasRole(role: MeshRole): Boolean {
        return meshRoles.contains(role)
    }
    
    /**
     * Check if node is a gateway (TOR, CLEARNET, or I2P)
     * @return true if this node offers any gateway role
     */
    fun isGatewayNode(): Boolean {
        return meshRoles.any { it in GATEWAY_ROLES }
    }
    
    /**
     * Calculate gateway suitability score (0.0-1.0)
     * Higher is better for routing decisions.
     * Only meaningful for nodes with gateway roles.
     * 
     * Algorithm:
     * - 30% centrality (network position)
     * - 40% fitness (hardware capability)
     * - 30% latency (response time)
     * 
     * @param gatewayType The gateway role to calculate suitability for
     * @return Suitability score 0.0-1.0, or 0 if node doesn't have this role
     */
    fun calculateGatewaySuitability(gatewayType: MeshRole): Float {
        if (!hasRole(gatewayType)) return 0f

        // Normalize latency: 0ms = 1.0, 1000ms = 0.0
        val normalizedLatency = 1f - (pingTime / 1000f).coerceIn(0f, 1f)

        // Normalize RSSI [-90,-30] dBm → [0.0,1.0]; 0 (unknown) = 0.5 neutral
        val signalQuality = if (internetSignalStrengthDbm != 0) {
            ((internetSignalStrengthDbm.toFloat() + 90f) / 60f).coerceIn(0f, 1f)
        } else 0.5f

        // Weights: 25% centrality + 35% fitness + 25% latency + 15% signal
        return (centralityScore * 0.25f) +
               (fitnessScore * 0.35f) +
               (normalizedLatency * 0.25f) +
               (signalQuality * 0.15f)
    }
    
    /**
     * Check if this topology info is stale (older than threshold)
     * @param thresholdMs Age threshold in milliseconds (default 30 seconds)
     * @return true if this node info is older than threshold
     */
    fun isStale(thresholdMs: Long = 30_000): Boolean {
        return (System.currentTimeMillis() - lastSeen) > thresholdMs
    }
    
    companion object {
        /**
         * Gateway roles used for packet routing
         */
        val GATEWAY_ROLES = setOf(
            MeshRole.TOR_GATEWAY,
            MeshRole.CLEARNET_GATEWAY,
            MeshRole.I2P_GATEWAY
        )
        
        /**
         * Intelligence roles used for future mesh optimization
         */
        val INTELLIGENCE_ROLES = setOf(
            MeshRole.STORAGE_NODE,
            MeshRole.COMPUTE_NODE,
            MeshRole.MESH_ROUTER
        )
    }
}
