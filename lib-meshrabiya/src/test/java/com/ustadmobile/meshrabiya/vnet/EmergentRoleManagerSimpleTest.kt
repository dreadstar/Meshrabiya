package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.mmcp.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

/**
 * Unit tests for EmergentRoleManager focusing on core functionality
 * and individual method behavior
 */
class EmergentRoleManagerSimpleTest {

    private lateinit var mockVirtualNode: VirtualNode
    private lateinit var mockContext: Context
    private lateinit var mockMeshRoleManager: MeshRoleManager
    private lateinit var emergentRoleManager: EmergentRoleManager

    @Before
    fun setup() {
        mockVirtualNode = mock(VirtualNode::class.java)
        mockContext = mock(Context::class.java)
        mockMeshRoleManager = mock(MeshRoleManager::class.java)
        
        // Setup basic virtual node behavior
        whenever(mockVirtualNode.neighbors()).thenReturn(emptyList())
        whenever(mockMeshRoleManager.userAllowsTorProxy).thenReturn(true)
        
        emergentRoleManager = EmergentRoleManager(mockVirtualNode, mockContext, mockMeshRoleManager)
    }

    // ===== BASIC FUNCTIONALITY TESTS =====

    @Test
    fun testBasicParticipantRoleAssignment() {
        val basicNode = createBasicNode()
        val plan = emergentRoleManager.determineOptimalRoles(basicNode)
        
        assertTrue(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), 
            "Every node should be assigned participant role")
    }

    @Test
    fun testHighCapabilityNodeGetsMultipleRoles() {
        val highCapNode = createHighCapabilityNode()
        val needyMesh = createHighDemandMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(highCapNode, needyMesh)
        
        assertTrue(plan.addRoles.size > 1, "High capability node should get multiple roles")
        assertContains(plan.addRoles, MeshRole.MESH_PARTICIPANT, "Should include participant role")
    }

    @Test
    fun testLowCapabilityNodeGetsMinimalRoles() {
        val lowCapNode = createLowCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(lowCapNode)
        
        assertEquals(setOf(MeshRole.MESH_PARTICIPANT), plan.addRoles,
            "Low capability node should only get participant role")
    }

    // ===== GATEWAY ROLE TESTS =====

    @Test
    fun testTorGatewayAssignmentWhenTorAllowed() {
        whenever(mockMeshRoleManager.userAllowsTorProxy).thenReturn(true)
        
        val stableNode = createStableHighBandwidthNode()
        val gatewayNeededMesh = createGatewayNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(stableNode, gatewayNeededMesh)
        
        assertTrue(plan.addRoles.contains(MeshRole.TOR_GATEWAY), 
            "Should assign Tor gateway when user allows Tor proxy")
    }

    @Test
    fun testClearnetGatewayAssignmentForHighBandwidth() {
        whenever(mockMeshRoleManager.userAllowsTorProxy).thenReturn(false)
        
        val highBandwidthNode = createHighBandwidthNode()
        val gatewayNeededMesh = createGatewayNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(highBandwidthNode, gatewayNeededMesh)
        
        assertTrue(plan.addRoles.contains(MeshRole.CLEARNET_GATEWAY), 
            "Should assign clearnet gateway for high bandwidth when Tor not allowed")
    }

    @Test
    fun testNoGatewayRoleForUnstableConnection() {
        val unstableNode = createUnstableNode()
        val gatewayNeededMesh = createGatewayNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(unstableNode, gatewayNeededMesh)
        
        assertFalse(plan.addRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY) },
            "Should not assign gateway roles to unstable nodes")
    }

    // ===== STORAGE ROLE TESTS =====

    @Test
    fun testStorageRoleAssignmentWithSufficientStorage() {
        val storageCapableNode = createStorageCapableNode()
        val storageNeededMesh = createStorageNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(storageCapableNode, storageNeededMesh)
        
        assertTrue(plan.addRoles.contains(MeshRole.STORAGE_NODE),
            "Should assign storage role to node with sufficient storage")
    }

    @Test
    fun testNoStorageRoleWhenThrottling() {
        val throttlingNode = createThrottlingNode()
        val storageNeededMesh = createStorageNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(throttlingNode, storageNeededMesh)
        
        assertFalse(plan.addRoles.contains(MeshRole.STORAGE_NODE),
            "Should not assign storage role when node is throttling")
    }

    @Test
    fun testNoStorageRoleWithInsufficientStorage() {
        val lowStorageNode = createLowStorageNode()
        val storageNeededMesh = createStorageNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(lowStorageNode, storageNeededMesh)
        
        assertFalse(plan.addRoles.contains(MeshRole.STORAGE_NODE),
            "Should not assign storage role with insufficient storage")
    }

    // ===== COMPUTE ROLE TESTS =====

    @Test
    fun testComputeRoleAssignmentWithGoodConditions() {
        val computeCapableNode = createComputeCapableNode()
        val computeNeededMesh = createComputeNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(computeCapableNode, computeNeededMesh)
        
        assertTrue(plan.addRoles.contains(MeshRole.COMPUTE_NODE),
            "Should assign compute role under good conditions")
    }

    @Test
    fun testNoComputeRoleWithLowBattery() {
        val lowBatteryNode = createLowBatteryNode()
        val computeNeededMesh = createComputeNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(lowBatteryNode, computeNeededMesh)
        
        assertFalse(plan.addRoles.contains(MeshRole.COMPUTE_NODE),
            "Should not assign compute role with low battery")
    }

    @Test
    fun testComputeRoleWithChargingOverridesLowBattery() {
        val chargingLowBatteryNode = createChargingLowBatteryNode()
        val computeNeededMesh = createComputeNeededMesh()
        
        val plan = emergentRoleManager.determineOptimalRoles(chargingLowBatteryNode, computeNeededMesh)
        
        assertTrue(plan.addRoles.contains(MeshRole.COMPUTE_NODE),
            "Should assign compute role when charging, even with low battery")
    }

    // ===== ROUTER ROLE TESTS =====

    @Test
    fun testRouterRoleWithMultipleNeighbors() {
        val neighborList = mutableListOf<Pair<Int, VirtualNode.LastOriginatorMessage>>()
        repeat(3) { i ->
            val neighborMessage = mock(VirtualNode.LastOriginatorMessage::class.java)
            neighborList.add(Pair(i, neighborMessage))
        }
        whenever(mockVirtualNode.neighbors()).thenReturn(neighborList)
        
        val routerCapableNode = createMediumCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(routerCapableNode)
        
        assertTrue(plan.addRoles.contains(MeshRole.MESH_ROUTER),
            "Should assign router role with multiple neighbors")
    }

    @Test
    fun testNoRouterRoleWithFewNeighbors() {
        whenever(mockVirtualNode.neighbors()).thenReturn(emptyList())
        
        val isolatedNode = createMediumCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(isolatedNode)
        
        assertFalse(plan.addRoles.contains(MeshRole.MESH_ROUTER),
            "Should not assign router role without sufficient neighbors")
    }

    // ===== COORDINATOR ROLE TESTS =====

    @Test
    fun testCoordinatorRoleForHighlyConnectedStableNode() {
        val neighborList = mutableListOf<Pair<Int, VirtualNode.LastOriginatorMessage>>()
        repeat(5) { i ->
            val neighborMessage = mock(VirtualNode.LastOriginatorMessage::class.java)
            neighborList.add(Pair(i, neighborMessage))
        }
        whenever(mockVirtualNode.neighbors()).thenReturn(neighborList)
        
        val excellentNode = createExcellentNode()
        val plan = emergentRoleManager.determineOptimalRoles(excellentNode)
        
        assertTrue(plan.addRoles.contains(MeshRole.COORDINATOR),
            "Should assign coordinator role to excellent, well-connected node")
    }

    @Test
    fun testNoCoordinatorRoleForUnstableNode() {
        val unstableNode = createUnstableNode()
        val plan = emergentRoleManager.determineOptimalRoles(unstableNode)
        
        assertFalse(plan.addRoles.contains(MeshRole.COORDINATOR),
            "Should not assign coordinator role to unstable node")
    }

    // ===== MESH INTELLIGENCE TESTS =====

    @Test
    fun testNoNewRolesWhenMeshSaturated() {
        val saturatedMesh = MeshIntelligence(
            totalNodes = 10,
            activeGateways = 8, // 80% are gateways
            activeStorageNodes = 9, // 90% are storage
            activeComputeNodes = 8, // 80% are compute
            networkLoad = 0.1f,
            storageUtilization = 0.1f,
            computeUtilization = 0.1f
        )
        
        val goodNode = createMediumCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(goodNode, saturatedMesh)
        
        // Should be conservative when mesh doesn't need more roles
        val specializedRoles = plan.addRoles - MeshRole.MESH_PARTICIPANT
        assertTrue(specializedRoles.size <= 1, 
            "Should assign few specialized roles when mesh is saturated")
    }

    // ===== HELPER METHODS =====

    private fun createBasicNode() = NodeCapabilitySnapshot(
        nodeId = "basic-node",
        resources = ResourceCapabilities(
            availableCPU = 0.2f,
            availableRAM = 200_000_000L,
            availableBandwidth = 1_000_000L,
            storageOffered = 100_000L,
            batteryLevel = 50,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 50, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 35,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.WARM,
        networkQuality = 0.5f,
        stability = 0.6f
    )

    private fun createHighCapabilityNode() = NodeCapabilitySnapshot(
        nodeId = "high-capability-node",
        resources = ResourceCapabilities(
            availableCPU = 0.9f,
            availableRAM = 2_000_000_000L,
            availableBandwidth = 100_000_000L,
            storageOffered = 10_000_000L,
            batteryLevel = 100,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_HIGH,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 100, 
            isCharging = true, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 25,
            health = BatteryHealth.GOOD,
            chargingSource = ChargingSource.USB
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 1.0f,
        stability = 1.0f
    )

    private fun createLowCapabilityNode() = NodeCapabilitySnapshot(
        nodeId = "low-capability-node",
        resources = ResourceCapabilities(
            availableCPU = 0.1f,
            availableRAM = 50_000_000L,
            availableBandwidth = 100_000L,
            storageOffered = 10_000L,
            batteryLevel = 20,
            thermalThrottling = true,
            powerState = PowerState.BATTERY_LOW,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 20, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 50,
            health = BatteryHealth.POOR,
            chargingSource = null
        ),
        thermalState = ThermalState.HOT,
        networkQuality = 0.3f,
        stability = 0.4f
    )

    private fun createStableHighBandwidthNode() = NodeCapabilitySnapshot(
        nodeId = "stable-node",
        resources = ResourceCapabilities(
            availableCPU = 0.7f,
            availableRAM = 1_000_000_000L,
            availableBandwidth = 50_000_000L,
            storageOffered = 5_000_000L,
            batteryLevel = 80,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_HIGH,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 80, 
            isCharging = true, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 25,
            health = BatteryHealth.GOOD,
            chargingSource = ChargingSource.USB
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.9f,
        stability = 0.95f
    )

    private fun createHighBandwidthNode() = NodeCapabilitySnapshot(
        nodeId = "high-bandwidth-node",
        resources = ResourceCapabilities(
            availableCPU = 0.6f,
            availableRAM = 800_000_000L,
            availableBandwidth = 15_000_000L, // >10Mbps for clearnet
            storageOffered = 2_000_000L,
            batteryLevel = 70,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_HIGH,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 70, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 30,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.85f,
        stability = 0.8f
    )

    private fun createUnstableNode() = NodeCapabilitySnapshot(
        nodeId = "unstable-node",
        resources = ResourceCapabilities(
            availableCPU = 0.8f,
            availableRAM = 1_500_000_000L,
            availableBandwidth = 20_000_000L,
            storageOffered = 3_000_000L,
            batteryLevel = 60,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 60, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 40,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.WARM,
        networkQuality = 0.4f, // Poor network quality
        stability = 0.3f // Poor stability
    )

    private fun createStorageCapableNode() = NodeCapabilitySnapshot(
        nodeId = "storage-node",
        resources = ResourceCapabilities(
            availableCPU = 0.5f,
            availableRAM = 500_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 5_000_000L, // >1MB
            batteryLevel = 60,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 60, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 30,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createThrottlingNode() = NodeCapabilitySnapshot(
        nodeId = "throttling-node",
        resources = ResourceCapabilities(
            availableCPU = 0.5f,
            availableRAM = 500_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 5_000_000L,
            batteryLevel = 60,
            thermalThrottling = true,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 60, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 65,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.THROTTLING,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createLowStorageNode() = NodeCapabilitySnapshot(
        nodeId = "low-storage-node",
        resources = ResourceCapabilities(
            availableCPU = 0.5f,
            availableRAM = 500_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 500_000L, // <1MB
            batteryLevel = 60,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 60, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 30,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createComputeCapableNode() = NodeCapabilitySnapshot(
        nodeId = "compute-node",
        resources = ResourceCapabilities(
            availableCPU = 0.6f,
            availableRAM = 1_000_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 2_000_000L,
            batteryLevel = 80,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_HIGH,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 80, 
            isCharging = true, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 25,
            health = BatteryHealth.GOOD,
            chargingSource = ChargingSource.USB
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createLowBatteryNode() = NodeCapabilitySnapshot(
        nodeId = "low-battery-node",
        resources = ResourceCapabilities(
            availableCPU = 0.6f,
            availableRAM = 1_000_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 2_000_000L,
            batteryLevel = 25,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_LOW,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 25, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 35,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createChargingLowBatteryNode() = NodeCapabilitySnapshot(
        nodeId = "charging-low-battery-node",
        resources = ResourceCapabilities(
            availableCPU = 0.6f,
            availableRAM = 1_000_000_000L,
            availableBandwidth = 5_000_000L,
            storageOffered = 2_000_000L,
            batteryLevel = 25,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_LOW,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 25, 
            isCharging = true, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 30,
            health = BatteryHealth.GOOD,
            chargingSource = ChargingSource.USB
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createMediumCapabilityNode() = NodeCapabilitySnapshot(
        nodeId = "medium-node",
        resources = ResourceCapabilities(
            availableCPU = 0.5f,
            availableRAM = 500_000_000L,
            availableBandwidth = 10_000_000L,
            storageOffered = 1_000_000L,
            batteryLevel = 60,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 60, 
            isCharging = false, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 35,
            health = BatteryHealth.GOOD,
            chargingSource = null
        ),
        thermalState = ThermalState.WARM,
        networkQuality = 0.7f,
        stability = 0.8f
    )

    private fun createExcellentNode() = NodeCapabilitySnapshot(
        nodeId = "excellent-node",
        resources = ResourceCapabilities(
            availableCPU = 0.95f,
            availableRAM = 4_000_000_000L,
            availableBandwidth = 100_000_000L,
            storageOffered = 20_000_000L,
            batteryLevel = 100,
            thermalThrottling = false,
            powerState = PowerState.BATTERY_HIGH,
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = 100, 
            isCharging = true, 
            estimatedTimeRemaining = null,
            temperatureCelsius = 20,
            health = BatteryHealth.GOOD,
            chargingSource = ChargingSource.USB
        ),
        thermalState = ThermalState.COOL,
        networkQuality = 1.0f,
        stability = 1.0f
    )

    private fun createHighDemandMesh() = MeshIntelligence(
        totalNodes = 20,
        activeGateways = 1,
        activeStorageNodes = 2,
        activeComputeNodes = 1,
        networkLoad = 0.9f,
        storageUtilization = 0.8f,
        computeUtilization = 0.9f
    )

    private fun createGatewayNeededMesh() = MeshIntelligence(
        totalNodes = 50,
        activeGateways = 2, // Low number of gateways
        activeStorageNodes = 15,
        activeComputeNodes = 12,
        networkLoad = 0.9f, // High load indicates need for more gateways
        storageUtilization = 0.5f,
        computeUtilization = 0.5f
    )

    private fun createStorageNeededMesh() = MeshIntelligence(
        totalNodes = 30,
        activeGateways = 6,
        activeStorageNodes = 3, // Low number of storage nodes
        activeComputeNodes = 8,
        networkLoad = 0.5f,
        storageUtilization = 0.9f, // High utilization indicates need for more storage
        computeUtilization = 0.5f
    )

    private fun createComputeNeededMesh() = MeshIntelligence(
        totalNodes = 25,
        activeGateways = 5,
        activeStorageNodes = 8,
        activeComputeNodes = 2, // Low number of compute nodes
        networkLoad = 0.5f,
        storageUtilization = 0.5f,
        computeUtilization = 0.9f // High utilization indicates need for more compute
    )
}
