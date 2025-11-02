package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.hardware.DeviceCapabilityManager
import com.ustadmobile.meshrabiya.vnet.hardware.AndroidDeviceCapabilityManager
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities
import com.ustadmobile.meshrabiya.mmcp.BatteryInfo
import com.ustadmobile.meshrabiya.mmcp.ThermalState
import com.ustadmobile.meshrabiya.mmcp.PowerState
import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.util.WifiConcurrencyUtil

data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resources: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float,
    val stability: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
    val availableCPU: Float get() = resources.availableCPU
    val storageOffered: Long get() = resources.storageOffered
    val batteryLevel: Int get() = batteryInfo.level
    val isCharging: Boolean get() = batteryInfo.isCharging
}

data class VnetDeviceCapabilities(
    val storageAvailable: Long,
    val processingPower: Float,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float,
    val stability: Float
)

data class MeshIntelligence(
    val totalNodes: Int,
    val activeGateways: Int,
    val activeStorageNodes: Int,
    val activeComputeNodes: Int,
    val networkLoad: Float,
    val storageUtilization: Float,
    val computeUtilization: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    val needsMoreGateways: Boolean get() = activeGateways < (totalNodes * 0.2f) || networkLoad > 0.8f
    val needsMoreStorage: Boolean get() = activeStorageNodes < (totalNodes * 0.3f) || storageUtilization > 0.8f
    val needsMoreCompute: Boolean get() = activeComputeNodes < (totalNodes * 0.25f) || computeUtilization > 0.8f
}

data class RoleTransition(
    val toAdd: Set<MeshRole>,
    val toRemove: Set<MeshRole>
)

data class RoleTransitionPlan(
    val addRoles: Set<MeshRole>,
    val removeRoles: Set<MeshRole>,
    val transitionDeadline: Long,
    val fallbackNodes: Map<MeshRole, List<String>>
)

enum class RoleTransitionPriority {
    LOW, MODERATE, HIGH, IMMEDIATE
}

private fun RoleTransitionPlan.withPriority(priority: RoleTransitionPriority): RoleTransitionPlanWithPriority {
    return RoleTransitionPlanWithPriority(this, priority)
}

data class RoleTransitionPlanWithPriority(
    val plan: RoleTransitionPlan,
    val priority: RoleTransitionPriority
)

enum class GatewayMode { NONE, CLEARNET_GATEWAY, TOR_GATEWAY, I2P_GATEWAY }

class EmergentRoleManager(
    private val virtualNode: VirtualNode,
    private val context: Context,
    private val meshRoleManager: MeshRoleManager,
    private val meshTrafficRouter: Any? = null,
    private val distributedStorageManager: Any? = null,
    private val deviceCapabilityManager: DeviceCapabilityManager? = null,
    private val adaptivePowerManager: com.ustadmobile.meshrabiya.service.power.AdaptivePowerManager? = null
) {
    private val logger = try { BetaTestLogger.getInstance(context) } catch (e: Exception) { null }

    private val hardwareManager: DeviceCapabilityManager by lazy {
        deviceCapabilityManager ?: AndroidDeviceCapabilityManager(context, logger ?: BetaTestLogger.getInstance(context))
    }

    private val _powerConstraints = MutableStateFlow(PowerConstraints())
    val powerConstraints: StateFlow<PowerConstraints> = _powerConstraints.asStateFlow()

    data class PowerConstraints(
        val canProvideMLInference: Boolean = true,
        val canProvideStorage: Boolean = true,
        val canRelayTraffic: Boolean = true,
        val thermalState: String = "",
        val batteryLevel: Int = -1,
        val powerSavingMode: String = "BALANCED",
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private fun safeLog(level: LogLevel, message: String, throwable: Throwable? = null) {
        try {
            logger?.log(level, message, throwable)
        } catch (e: Exception) { }
    }

    private val _currentMeshRoles = MutableStateFlow<Set<MeshRole>>(setOf(MeshRole.MESH_PARTICIPANT))
    val currentMeshRoles: StateFlow<Set<MeshRole>> = _currentMeshRoles.asStateFlow()

    private val _meshIntelligence = MutableStateFlow(
        MeshIntelligence(
            totalNodes = 1,
            activeGateways = 0,
            activeStorageNodes = 0,
            activeComputeNodes = 0,
            networkLoad = 0.0f,
            storageUtilization = 0.0f,
            computeUtilization = 0.0f
        )
    )
    val meshIntelligence: StateFlow<MeshIntelligence> = _meshIntelligence.asStateFlow()

    private val _isRoleTransitionInProgress = MutableStateFlow(false)
    val isRoleTransitionInProgress: StateFlow<Boolean> = _isRoleTransitionInProgress.asStateFlow()

    private val _preferredRoles = MutableStateFlow<Set<MeshRole>>(emptySet())

    companion object {
        @Volatile
        private var instance: EmergentRoleManager? = null

        fun getInstance(
            context: Context,
            virtualNode: VirtualNode,
            meshRoleManager: MeshRoleManager,
            meshTrafficRouter: Any? = null,
            distributedStorageManager: Any? = null,
            deviceCapabilityManager: DeviceCapabilityManager? = null,
            adaptivePowerManager: com.ustadmobile.meshrabiya.service.power.AdaptivePowerManager? = null
        ): EmergentRoleManager {
            return instance ?: synchronized(this) {
                instance ?: EmergentRoleManager(
                    virtualNode,
                    context,
                    meshRoleManager,
                    meshTrafficRouter,
                    distributedStorageManager,
                    deviceCapabilityManager,
                    adaptivePowerManager
                ).also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    // --- NEW: Compute Node Participation Setter ---
    fun setComputeNodeParticipationEnabled(enabled: Boolean) {
        val current = _preferredRoles.value.toMutableSet()
        if (enabled) {
            current.add(MeshRole.COMPUTE_NODE)
        } else {
            current.remove(MeshRole.COMPUTE_NODE)
        }
        _preferredRoles.value = current
        updateRoles()
    }

    fun determineOptimalRoles(
        nodeCapabilities: NodeCapabilitySnapshot = getCurrentCapabilities(),
        meshIntelligence: MeshIntelligence = this.meshIntelligence.value,
        currentRoles: Set<MeshRole> = currentMeshRoles.value
    ): RoleTransitionPlan {
        val constrainedCapabilities = applyPowerConstraints(nodeCapabilities)
        val targetRoles = calculateTargetRoles(constrainedCapabilities, meshIntelligence)
        val transitions = planGracefulTransitions(currentRoles, targetRoles)
        return RoleTransitionPlan(
            addRoles = transitions.toAdd,
            removeRoles = transitions.toRemove,
            transitionDeadline = calculateTransitionTime(transitions),
            fallbackNodes = identifyFallbackNodes(meshIntelligence, transitions.toRemove)
        )
    }

    private fun calculateTargetRoles(
        node: NodeCapabilitySnapshot,
        mesh: MeshIntelligence
    ): Set<MeshRole> {
        val roles = mutableSetOf<MeshRole>()
        val userPreferences = _preferredRoles.value
        roles.add(MeshRole.MESH_PARTICIPANT)
        val fitness = calculateNormalizedFitness(node)
        if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
            val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
            roles.add(gatewayRole)
        }
        if (node.storageOffered > 1_000_000L &&
            fitness > 0.4 &&
            mesh.needsMoreStorage &&
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) &&
            (userPreferences.isEmpty() || MeshRole.STORAGE_NODE in userPreferences)) {
            roles.add(MeshRole.STORAGE_NODE)
        }
        if (node.availableCPU > 0.3f &&
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) &&
            (node.isCharging || node.batteryLevel > 30) &&
            mesh.needsMoreCompute &&
            (userPreferences.isEmpty() || MeshRole.COMPUTE_NODE in userPreferences)) {
            roles.add(MeshRole.COMPUTE_NODE)
        }
        // --- WiFi AP/Station concurrency check for MESH_ROUTER role ---
        val apStaSupported = WifiConcurrencyUtil.isApStaConcurrencySupported(context)
        if (fitness > 0.6 && virtualNode.neighbors().size >= 2 && apStaSupported) {
            roles.add(MeshRole.MESH_ROUTER)
        } else {
            roles.remove(MeshRole.MESH_ROUTER)
        }
        if (fitness > 0.85 &&
            node.hasStableConnection() &&
            virtualNode.neighbors().size >= 3 &&
            (userPreferences.isEmpty() || MeshRole.COORDINATOR in userPreferences)) {
            roles.add(MeshRole.COORDINATOR)
        }
        return roles
    }

    private fun selectBestGatewayRole(
        node: NodeCapabilitySnapshot,
        mesh: MeshIntelligence,
        userPreferences: Set<MeshRole>
    ): MeshRole {
        val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
        val preferredGateways = userPreferences.intersect(gatewayRoles)
        if (preferredGateways.isNotEmpty()) {
            return preferredGateways.first()
        }
        return when {
            !meshRoleManager.userAllowsTorProxy && node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
            meshRoleManager.userAllowsTorProxy -> MeshRole.TOR_GATEWAY
            node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
            else -> MeshRole.TOR_GATEWAY
        }
    }

    private fun calculateNormalizedFitness(node: NodeCapabilitySnapshot): Float {
        val batteryScore = when {
            node.isCharging -> 1.0f
            node.batteryLevel > 70 -> 0.9f
            node.batteryLevel > 30 -> 0.6f
            else -> 0.3f
        }
        val thermalScore = when (node.thermalState) {
            ThermalState.COOL -> 1.0f
            ThermalState.WARM -> 0.8f
            ThermalState.HOT -> 0.5f
            ThermalState.THROTTLING -> 0.2f
            ThermalState.CRITICAL -> 0.1f
        }
        val connectivityScore = node.networkQuality
        val stabilityScore = node.stability
        return (batteryScore * 0.3f +
                thermalScore * 0.2f +
                connectivityScore * 0.3f +
                stabilityScore * 0.2f).coerceIn(0.0f, 1.0f)
    }

    private fun planGracefulTransitions(
        currentRoles: Set<MeshRole>,
        targetRoles: Set<MeshRole>
    ): RoleTransition {
        val toAdd = targetRoles - currentRoles
        val toRemove = currentRoles - targetRoles
        val safeToRemove = toRemove.filter { role ->
            when (role) {
                MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY -> meshIntelligence.value.activeGateways > 1
                MeshRole.COORDINATOR -> true
                else -> true
            }
        }.toSet()
        return RoleTransition(toAdd, safeToRemove)
    }

    private fun calculateTransitionTime(transitions: RoleTransition): Long {
        val baseDelay = when {
            transitions.toRemove.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY) } -> 5.minutes.inWholeMilliseconds
            transitions.toRemove.isNotEmpty() -> 2.minutes.inWholeMilliseconds
            else -> 30_000L
        }
        return System.currentTimeMillis() + baseDelay
    }

    private fun identifyFallbackNodes(
        mesh: MeshIntelligence,
        rolesToRemove: Set<MeshRole>
    ): Map<MeshRole, List<String>> {
        return emptyMap()
    }

    fun getCurrentCapabilities(): NodeCapabilitySnapshot {
        return try {
            val nodeId = virtualNode.addressAsInt.toString()
            val snapshot = runBlocking { hardwareManager.getCapabilitySnapshot(nodeId) }
            val storageOffered = calculateAvailableStorage()
            val enhancedResources = snapshot.resources.copy(
                storageOffered = maxOf(snapshot.resources.storageOffered, storageOffered)
            )
            val enhancedSnapshot = snapshot.copy(resources = enhancedResources)
            logger?.log(LogLevel.INFO, "EmergentRoleManager",
                "Hardware capabilities: CPU=${(enhancedSnapshot.resources.availableCPU * 100).toInt()}% available, " +
                "Battery=${enhancedSnapshot.batteryInfo.level}%, " +
                "Storage=${enhancedSnapshot.resources.storageOffered / (1024 * 1024)}MB offered, " +
                "Thermal=${enhancedSnapshot.thermalState}, " +
                "Stability=${(enhancedSnapshot.stability * 100).toInt()}%")
            enhancedSnapshot
        } catch (e: Exception) {
            logger?.log(LogLevel.BASIC, "EmergentRoleManager",
                "Hardware metrics unavailable, using fallback: ${e.message}")
            val fitnessScore = meshRoleManager.calculateFitnessScore()
            val storageOffered = calculateAvailableStorage()
            val resources = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = Runtime.getRuntime().freeMemory(),
                availableBandwidth = 10_000_000L,
                storageOffered = storageOffered,
                batteryLevel = fitnessScore.batteryLevel.toInt().coerceIn(0, 100),
                thermalThrottling = false,
                powerState = if (fitnessScore.batteryLevel > 0.7f) PowerState.BATTERY_HIGH else PowerState.BATTERY_MEDIUM,
                networkInterfaces = emptySet()
            )
            val batteryInfo = BatteryInfo(
                level = fitnessScore.batteryLevel.toInt().coerceIn(0, 100),
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = com.ustadmobile.meshrabiya.mmcp.BatteryHealth.GOOD,
                chargingSource = null
            )
            NodeCapabilitySnapshot(
                nodeId = virtualNode.addressAsInt.toString(),
                resources = resources,
                batteryInfo = batteryInfo,
                thermalState = ThermalState.COOL,
                networkQuality = (fitnessScore.signalStrength / 100.0f).coerceIn(0.0f, 1.0f),
                stability = 0.8f
            )
        }
    }

    fun calculateAvailableStorage(): Long {
        return try {
            distributedStorageManager?.let { storageManager ->
                val getStorageCapabilitiesMethod = storageManager.javaClass.getMethod("getStorageCapabilities")
                val capabilities = getStorageCapabilitiesMethod.invoke(storageManager)
                val totalOfferedField = capabilities.javaClass.getDeclaredField("totalOffered")
                totalOfferedField.isAccessible = true
                totalOfferedField.getLong(capabilities)
            } ?: 100_000_000L
        } catch (e: Exception) {
            100_000_000L
        }
    }

    fun isStorageNode(): Boolean {
        return currentMeshRoles.value.contains(MeshRole.STORAGE_NODE)
    }

    fun getSystemState(): String {
        return getCurrentCapabilities().thermalState.name.lowercase()
    }

    fun calculateFitnessScore(): Float {
        val node = getCurrentCapabilities()
        return calculateNormalizedFitness(node)
    }

    /**
     * Returns true if this node has the file with the given fileId (i.e., is a replica).
     * Used by MeshGossipService for ReplicaQuery.
     */
    fun hasFile(fileId: String): Boolean {
        // This should check the local storage/dataStore for the fileId.
        // For production, this should be implemented using the DataStore or direct file check.
        // Placeholder: always returns false unless overridden.
        // TODO: Replace with actual implementation using DataStore or file system.
        return try {
            // Example: dataStore.hasFile(fileId)
            // return dataStore.hasFile(fileId)
            false
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Error checking hasFile($fileId): ${e.message}")
            false
        }
    }

    fun updateMeshIntelligence(intelligence: MeshIntelligence) {
        _meshIntelligence.value = intelligence
        safeLog(LogLevel.DEBUG, "Updated mesh intelligence: $intelligence")
    }

    fun processNodeAnnouncement(nodeId: String, meshRoles: Set<MeshRole>) {
        val current = _meshIntelligence.value
        val activeGateways = if (meshRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY) }) {
            current.activeGateways + 1
        } else {
            current.activeGateways
        }
        val activeStorageNodes = if (MeshRole.STORAGE_NODE in meshRoles) {
            current.activeStorageNodes + 1
        } else {
            current.activeStorageNodes
        }
        val activeComputeNodes = if (MeshRole.COMPUTE_NODE in meshRoles) {
            current.activeComputeNodes + 1
        } else {
            current.activeComputeNodes
        }
        val totalNodes = virtualNode.neighbors().size + 1
        val updated = current.copy(
            totalNodes = totalNodes,
            activeGateways = activeGateways.coerceAtMost(totalNodes),
            activeStorageNodes = activeStorageNodes.coerceAtMost(totalNodes),
            activeComputeNodes = activeComputeNodes.coerceAtMost(totalNodes),
            networkLoad = estimateNetworkLoad(),
            storageUtilization = estimateStorageUtilization(),
            computeUtilization = estimateComputeUtilization()
        )
        _meshIntelligence.value = updated
        safeLog(LogLevel.DEBUG, "Updated mesh intelligence from node $nodeId: $updated")
    }

    private fun estimateNetworkLoad(): Float = 0.3f
    private fun estimateStorageUtilization(): Float = 0.2f
    private fun estimateComputeUtilization(): Float = 0.1f

    fun applyTransitionPlan(plan: RoleTransitionPlan) {
        val currentRoles = _currentMeshRoles.value.toMutableSet()
        currentRoles.addAll(plan.addRoles)
        currentRoles.removeAll(plan.removeRoles)
        _currentMeshRoles.value = currentRoles
        handleGatewayRoleTransitions(plan.addRoles, plan.removeRoles)
        safeLog(LogLevel.INFO, "Applied role transition: +${plan.addRoles}, -${plan.removeRoles}")
        safeLog(LogLevel.INFO, "Current roles: $currentRoles")
    }

    private fun executeRoleTransition(plan: RoleTransitionPlan) {
        safeLog(LogLevel.INFO, "Executing role transition: +${plan.addRoles}, -${plan.removeRoles}")
        applyTransitionPlan(plan)
    }

    private fun handleGatewayRoleTransitions(addedRoles: Set<MeshRole>, removedRoles: Set<MeshRole>) {
        try {
            when {
                MeshRole.TOR_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating Tor gateway routing")
                    activateGatewayRouting(GatewayMode.TOR_GATEWAY)
                    CoroutineScope(Dispatchers.IO).launch { announceGatewayCapability() }
                }
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating clearnet gateway routing")
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                    CoroutineScope(Dispatchers.IO).launch { announceGatewayCapability() }
                }
            }
            val gatewayRolesRemoved = removedRoles.intersect(
                setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
            )
            if (gatewayRolesRemoved.isNotEmpty()) {
                safeLog(LogLevel.INFO, "EmergentRole: Deactivating gateway routing")
                deactivateGatewayRouting()
            }
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to handle gateway role transitions: ${e.message}")
        }
    }

    private fun activateGatewayRouting(mode: GatewayMode) {
        try {
            meshTrafficRouter?.let { router ->
                try {
                    when (mode) {
                        GatewayMode.TOR_GATEWAY -> {
                            val enableMethod = router.javaClass.getMethod("enableGatewayRouting", Any::class.java)
                            val routingModeClass = Class.forName("com.ustadmobile.orbotmeshrabiyaintegration.MeshTrafficRouter\$RoutingMode")
                            val torOnlyMode = routingModeClass.enumConstants?.find { it.toString() == "TOR_ONLY" }
                            enableMethod.invoke(router, torOnlyMode)
                        }
                        GatewayMode.CLEARNET_GATEWAY -> {
                            val enableMethod = router.javaClass.getMethod("enableGatewayRouting", Any::class.java)
                            val routingModeClass = Class.forName("com.ustadmobile.orbotmeshrabiyaintegration.MeshTrafficRouter\$RoutingMode")
                            val clearnetMode = routingModeClass.enumConstants?.find { it.toString() == "CLEARNET_DIRECT" }
                            enableMethod.invoke(router, clearnetMode)
                        }
                        GatewayMode.NONE -> { }
                        GatewayMode.I2P_GATEWAY -> { }
                    }
                } catch (reflectionException: Exception) {
                    safeLog(LogLevel.WARN, "Failed to activate gateway routing via reflection: ${reflectionException.message}")
                }
            }
            if (virtualNode is AndroidVirtualNode) {
                safeLog(LogLevel.DEBUG, "EmergentRole: AndroidVirtualNode gateway integration active")
            }
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to activate gateway routing: ${e.message}")
        }
    }

    private fun deactivateGatewayRouting() {
        try {
            meshTrafficRouter?.let { router ->
                try {
                    val disableMethod = router.javaClass.getMethod("disableGatewayRouting")
                    disableMethod.invoke(router)
                } catch (reflectionException: Exception) {
                    safeLog(LogLevel.WARN, "Failed to deactivate gateway routing via reflection: ${reflectionException.message}")
                }
            }
            if (virtualNode is AndroidVirtualNode) {
                safeLog(LogLevel.DEBUG, "AndroidVirtualNode gateway integration deactivated")
            }
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Failed to deactivate gateway routing: ${e.message}", e)
        }
    }

    private suspend fun announceGatewayCapability() {
        val startTime = System.currentTimeMillis()
        try {
            val gatewayType = when {
                hasI2PSupport() -> MmcpGatewayAnnouncement.GatewayType.I2P
                hasTorSupport() -> MmcpGatewayAnnouncement.GatewayType.TOR
                else -> MmcpGatewayAnnouncement.GatewayType.CLEARNET
            }
            val bandwidthCapacity = estimateNetworkCapacity()
            val networkLatency = measureNetworkLatency()
            val supportedProtocols = getSupportedProtocols(gatewayType)
            val announcement = MmcpGatewayAnnouncement(
                nodeId = virtualNode.addressAsInt.toString(),
                gatewayType = gatewayType,
                capacity = bandwidthCapacity,
                latency = networkLatency,
                isActive = true,
                supportedProtocols = supportedProtocols,
                requestedMessageId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            )
            virtualNode.sendMessage(announcement)
            val totalTime = System.currentTimeMillis() - startTime
            safeLog(LogLevel.INFO, "Gateway capability announced successfully - gatewayType: $gatewayType, protocols: ${supportedProtocols.joinToString(",")}, uploadBandwidth: ${bandwidthCapacity.uploadMbps}Mbps, downloadBandwidth: ${bandwidthCapacity.downloadMbps}Mbps, latency: ${networkLatency.averageMs}ms, totalTimeMs: $totalTime")
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            safeLog(LogLevel.ERROR, "Failed to announce gateway capability: ${e.message}", e)
        }
    }

    private suspend fun estimateNetworkCapacity(): MmcpGatewayAnnouncement.BandwidthCapacity {
        return try {
            val estimatedBandwidth = deviceCapabilityManager?.getEstimatedBandwidth() ?: 1000000L
            val uploadBytesPerSecond = (estimatedBandwidth * 0.1).toLong()
            val downloadBytesPerSecond = (estimatedBandwidth * 0.9).toLong()
            MmcpGatewayAnnouncement.BandwidthCapacity(
                uploadMbps = uploadBytesPerSecond / 1_000_000f,
                downloadMbps = downloadBytesPerSecond / 1_000_000f
            )
        } catch (e: Exception) {
            MmcpGatewayAnnouncement.BandwidthCapacity(
                uploadMbps = 1f,
                downloadMbps = 10f
            )
        }
    }

    private suspend fun measureNetworkLatency(): MmcpGatewayAnnouncement.NetworkLatency {
        return try {
            val networkInterfaces = deviceCapabilityManager?.getNetworkInterfaces() ?: emptyList()
            val averagePingMs = when {
                networkInterfaces.any { it.displayName?.contains("wifi", ignoreCase = true) == true } -> 30
                networkInterfaces.any { it.displayName?.contains("mobile", ignoreCase = true) == true } -> 80
                else -> 50
            }
            val jitterMs = averagePingMs / 10
            MmcpGatewayAnnouncement.NetworkLatency(
                averageMs = averagePingMs,
                jitterMs = jitterMs
            )
        } catch (e: Exception) {
            MmcpGatewayAnnouncement.NetworkLatency(
                averageMs = 100,
                jitterMs = 10
            )
        }
    }

    private fun hasI2PSupport(): Boolean {
        return false
    }

    private fun hasTorSupport(): Boolean {
        return true
    }

    private fun getSupportedProtocols(gatewayType: MmcpGatewayAnnouncement.GatewayType): Set<String> {
        return when (gatewayType) {
            MmcpGatewayAnnouncement.GatewayType.CLEARNET -> setOf("HTTP", "HTTPS", "DNS", "FTP")
            MmcpGatewayAnnouncement.GatewayType.TOR -> setOf("HTTP", "HTTPS", "SOCKS5")
            MmcpGatewayAnnouncement.GatewayType.I2P -> setOf("HTTP", "HTTPS", "I2P")
        }
    }

    fun updateRoles() {
        try {
            _isRoleTransitionInProgress.value = true
            val plan = determineOptimalRoles()
            if (plan.addRoles.isNotEmpty() || plan.removeRoles.isNotEmpty()) {
                applyTransitionPlan(plan)
            }
            meshRoleManager.updateRole()
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Error updating roles: ${e.message}")
        } finally {
            _isRoleTransitionInProgress.value = false
        }
    }

    fun getCurrentMeshRoles(): Set<MeshRole> = _currentMeshRoles.value
    fun getMeshIntelligence(): MeshIntelligence = _meshIntelligence.value
    fun isRoleTransitionInProgress(): Boolean = _isRoleTransitionInProgress.value
    fun setPreferredRoles(roles: Set<MeshRole>) {
        _preferredRoles.value = roles
        safeLog(LogLevel.INFO, "User set preferred roles: $roles")
    }
    fun getPreferredRoles(): Set<MeshRole> = _preferredRoles.value

    fun startHardwareMonitoring() {
        try {
            hardwareManager.startMonitoring(30000L)
            safeLog(LogLevel.INFO, "Started hardware monitoring for role optimization")
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to start hardware monitoring: ${e.message}")
        }
    }

    suspend fun updatePowerConstraints(
        canProvideMLInference: Boolean,
        canProvideStorage: Boolean,
        canRelayTraffic: Boolean,
        thermalState: String,
        batteryLevel: Int,
        powerSavingMode: String
    ) {
        val newConstraints = PowerConstraints(
            canProvideMLInference = canProvideMLInference,
            canProvideStorage = canProvideStorage,
            canRelayTraffic = canRelayTraffic,
            thermalState = thermalState,
            batteryLevel = batteryLevel,
            powerSavingMode = powerSavingMode,
            lastUpdated = System.currentTimeMillis()
        )
        val oldConstraints = _powerConstraints.value
        _powerConstraints.value = newConstraints
        safeLog(LogLevel.DEBUG, "Power constraints updated: ML=$canProvideMLInference, Storage=$canProvideStorage, Relay=$canRelayTraffic, Thermal=$thermalState, Battery=$batteryLevel%, Mode=$powerSavingMode")
        if (shouldReEvaluateRoles(oldConstraints, newConstraints)) {
            val currentCapabilities = getCurrentCapabilities()
            val constrainedCapabilities = applyPowerConstraints(currentCapabilities)
            val newPlan = determineOptimalRoles(constrainedCapabilities)
            executeRoleTransition(newPlan)
        }
    }

    private fun applyPowerConstraints(capabilities: NodeCapabilitySnapshot): NodeCapabilitySnapshot {
        val constraints = _powerConstraints.value
        val updatedResources = if (constraints.canProvideStorage) {
            capabilities.resources
        } else {
            capabilities.resources.copy(storageOffered = 0L)
        }
        val updatedBatteryInfo = if (constraints.batteryLevel >= 0) {
            capabilities.batteryInfo.copy(level = constraints.batteryLevel)
        } else {
            capabilities.batteryInfo
        }
        val updatedThermalState = if (constraints.thermalState.isNotBlank()) {
            when (constraints.thermalState.uppercase()) {
                "COOL" -> ThermalState.COOL
                "WARM" -> ThermalState.WARM
                "HOT" -> ThermalState.HOT
                "OVERHEATING" -> ThermalState.THROTTLING
                "CRITICAL" -> ThermalState.CRITICAL
                else -> capabilities.thermalState
            }
        } else {
            capabilities.thermalState
        }
        return capabilities.copy(
            resources = updatedResources,
            batteryInfo = updatedBatteryInfo,
            thermalState = updatedThermalState,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun shouldReEvaluateRoles(oldConstraints: PowerConstraints, newConstraints: PowerConstraints): Boolean {
        return oldConstraints.canProvideMLInference != newConstraints.canProvideMLInference ||
               oldConstraints.canProvideStorage != newConstraints.canProvideStorage ||
               oldConstraints.canRelayTraffic != newConstraints.canRelayTraffic ||
               getThermalSeverity(oldConstraints.thermalState) != getThermalSeverity(newConstraints.thermalState) ||
               getBatteryCategory(oldConstraints.batteryLevel) != getBatteryCategory(newConstraints.batteryLevel)
    }

    private fun getThermalPerformanceMultiplier(thermalState: String): Float {
        return when (thermalState.uppercase()) {
            "COOL" -> 1.0f
            "WARM" -> 0.9f
            "HOT" -> 0.7f
            "OVERHEATING" -> 0.5f
            "CRITICAL" -> 0.2f
            else -> 0.8f
        }
    }

    private fun getThermalSeverity(thermalState: String): Int {
        return when (thermalState.uppercase()) {
            "COOL" -> 0
            "WARM" -> 1
            "HOT" -> 2
            "OVERHEATING" -> 3
            "CRITICAL" -> 4
            else -> 2
        }
    }

    private fun getBatteryCategory(batteryLevel: Int): Int {
        return when {
            batteryLevel >= 80 -> 3
            batteryLevel >= 50 -> 2
            batteryLevel >= 20 -> 1
            else -> 0
        }
    }

    fun stopHardwareMonitoring() {
        try {
            hardwareManager.stopMonitoring()
            safeLog(LogLevel.INFO, "Stopped hardware monitoring")
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to stop hardware monitoring: ${e.message}")
        }
    }

    fun isHardwareMonitoring(): Boolean = hardwareManager.isMonitoring()

    fun getDeviceCapabilities(): NodeCapabilitySnapshot? {
        return try {
            runBlocking { hardwareManager.getCapabilitySnapshot(virtualNode.addressAsInt.toString()) }
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to get device capabilities: ${e.message}")
            null
        }
    }
}