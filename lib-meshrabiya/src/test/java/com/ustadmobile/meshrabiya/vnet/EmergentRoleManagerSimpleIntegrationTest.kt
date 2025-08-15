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
 * Comprehensive integration tests for EmergentRoleManager covering performance
 * and edge case scenarios
 */
class EmergentRoleManagerSimpleIntegrationTest {

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

    // ===== PERFORMANCE TESTS =====

    @Test
    fun testMassiveNetworkScaling() {
        // Test with a very large mesh network (1000+ nodes)
        val massiveMeshIntelligence = MeshIntelligence(
            totalNodes = 1000,
            activeGateways = 50,
            activeStorageNodes = 200,
            activeComputeNodes = 150,
            networkLoad = 0.6f,
            storageUtilization = 0.7f,
            computeUtilization = 0.5f
        )
        
        val highCapabilityNode = createHighCapabilityNode()
        
        val plan = emergentRoleManager.determineOptimalRoles(
            nodeCapabilities = highCapabilityNode,
            meshIntelligence = massiveMeshIntelligence
        )
        
        // In a massive network with adequate resources, high capability nodes should still get meaningful roles
        assertTrue(plan.addRoles.isNotEmpty(), "High capability node should receive roles in massive network")
        assertTrue(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), "Should always include participant role")
    }

    @Test
    fun testRapidRoleTransitionCycles() {
        // Test rapid successive role calculations to ensure performance
        val baseNode = createMediumCapabilityNode()
        val meshIntelligence = createBalancedMeshIntelligence()
        
        val startTime = System.currentTimeMillis()
        
        // Perform 100 rapid role calculations
        repeat(100) {
            emergentRoleManager.determineOptimalRoles(baseNode, meshIntelligence)
        }
        
        val endTime = System.currentTimeMillis()
        val executionTime = endTime - startTime
        
        // Should complete 100 calculations in under 1 second (reasonable performance expectation)
        assertTrue(executionTime < 1000, "100 role calculations should complete in under 1 second, took ${executionTime}ms")
    }

    @Test
    fun testMemoryLeakPreventionDuringLongRunning() {
        // Test that repeated operations don't cause memory accumulation
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        
        repeat(1000) { iteration ->
            val dynamicNode = createDynamicCapabilityNode(iteration)
            val dynamicMesh = createDynamicMeshIntelligence(iteration)
            
            emergentRoleManager.determineOptimalRoles(dynamicNode, dynamicMesh)
            
            // Force garbage collection every 100 iterations
            if (iteration % 100 == 0) {
                System.gc()
                Thread.sleep(10) // Give GC time to work
            }
        }
        
        System.gc()
        Thread.sleep(50)
        
        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        // Memory increase should be reasonable (less than 10MB for this test)
        assertTrue(memoryIncrease < 10_000_000, "Memory increase should be < 10MB, was ${memoryIncrease / 1_000_000}MB")
    }

    // ===== EDGE CASE TESTS =====

    @Test
    fun testZeroNodeNetwork() {
        val emptyMesh = MeshIntelligence(
            totalNodes = 0,
            activeGateways = 0,
            activeStorageNodes = 0,
            activeComputeNodes = 0,
            networkLoad = 0.0f,
            storageUtilization = 0.0f,
            computeUtilization = 0.0f
        )
        
        val node = createHighCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(node, emptyMesh)
        
        // Should handle empty network gracefully
        assertTrue(plan.addRoles.isNotEmpty(), "Should assign roles even in empty network")
        assertContains(plan.addRoles, MeshRole.MESH_PARTICIPANT, "Should always include participant role")
    }

    @Test
    fun testCriticalBatteryScenarios() {
        // Test node with critically low battery (1%)
        val criticalBatteryNode = NodeCapabilitySnapshot(
            nodeId = "critical-battery-node",
            resources = ResourceCapabilities(
                availableCPU = 0.9f,
                availableRAM = 1_000_000_000L,
                availableBandwidth = 100_000_000L,
                storageOffered = 10_000_000L,
                batteryLevel = 1,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_LOW,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 1, 
                isCharging = false, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL,
            networkQuality = 1.0f,
            stability = 1.0f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(criticalBatteryNode)
        
        // Should be very conservative with role assignment
        assertFalse(plan.addRoles.contains(MeshRole.COMPUTE_NODE), "Should not assign compute role with critical battery")
        // May still allow participant role for basic mesh functionality
        assertTrue(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), "Should maintain basic participation")
    }

    @Test
    fun testThermalThrottlingEdgeCase() {
        val overheatingNode = NodeCapabilitySnapshot(
            nodeId = "overheating-node",
            resources = ResourceCapabilities(
                availableCPU = 0.9f,
                availableRAM = 1_000_000_000L,
                availableBandwidth = 100_000_000L,
                storageOffered = 10_000_000L,
                batteryLevel = 100,
                thermalThrottling = true,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100, 
                isCharging = true, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 80,
                health = BatteryHealth.GOOD,
                chargingSource = ChargingSource.USB
            ),
            thermalState = ThermalState.CRITICAL,
            networkQuality = 1.0f,
            stability = 1.0f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(overheatingNode)
        
        // Should avoid compute and storage roles due to thermal state
        assertFalse(plan.addRoles.contains(MeshRole.COMPUTE_NODE), "Should not assign compute role when overheating")
        assertFalse(plan.addRoles.contains(MeshRole.STORAGE_NODE), "Should not assign storage role when overheating")
    }

    @Test
    fun testNetworkInstabilityScenarios() {
        val unstableNode = NodeCapabilitySnapshot(
            nodeId = "unstable-node",
            resources = ResourceCapabilities(
                availableCPU = 0.9f,
                availableRAM = 1_000_000_000L,
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
            networkQuality = 0.1f, // Very poor network
            stability = 0.1f // Very unstable
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(unstableNode)
        
        // Should not assign gateway roles with poor connectivity
        assertFalse(plan.addRoles.contains(MeshRole.TOR_GATEWAY), "Should not assign gateway role with poor connectivity")
        assertFalse(plan.addRoles.contains(MeshRole.CLEARNET_GATEWAY), "Should not assign clearnet gateway with poor connectivity")
    }

    @Test
    fun testExtremeResourceConstraints() {
        val constrainedNode = NodeCapabilitySnapshot(
            nodeId = "constrained-node",
            resources = ResourceCapabilities(
                availableCPU = 0.01f, // 1% CPU
                availableRAM = 1000L,
                availableBandwidth = 1000L, // 1KB/s
                storageOffered = 100L, // 100 bytes
                batteryLevel = 5,
                thermalThrottling = true,
                powerState = PowerState.BATTERY_LOW,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 5, 
                isCharging = false, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 60,
                health = BatteryHealth.POOR,
                chargingSource = null
            ),
            thermalState = ThermalState.HOT,
            networkQuality = 0.2f,
            stability = 0.3f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(constrainedNode)
        
        // Should only get basic participant role
        assertEquals(setOf(MeshRole.MESH_PARTICIPANT), plan.addRoles, 
            "Extremely constrained node should only get participant role")
    }

    @Test
    fun testSaturatedNetworkScenario() {
        // Network with way too many of each role type
        val saturatedMesh = MeshIntelligence(
            totalNodes = 100,
            activeGateways = 90, // 90% are gateways (oversaturated)
            activeStorageNodes = 95, // 95% are storage nodes
            activeComputeNodes = 85, // 85% are compute nodes
            networkLoad = 0.1f, // Very low load
            storageUtilization = 0.1f, // Very low utilization
            computeUtilization = 0.1f
        )
        
        val perfectNode = createHighCapabilityNode()
        val plan = emergentRoleManager.determineOptimalRoles(perfectNode, saturatedMesh)
        
        // Should be conservative about adding more roles when network is saturated
        // Focus should be on basic roles like participant and router
        assertTrue(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), "Should always have participant role")
    }

    @Test
    fun testFragmentedNetworkTopology() {
        // Simulate a node with many neighbors (hub node in fragmented network)
        val neighborList = mutableListOf<Pair<Int, VirtualNode.LastOriginatorMessage>>()
        repeat(20) { i ->
            val neighborMessage = mock(VirtualNode.LastOriginatorMessage::class.java)
            neighborList.add(Pair(i, neighborMessage))
        }
        whenever(mockVirtualNode.neighbors()).thenReturn(neighborList)
        
        val hubNode = createHighCapabilityNode()
        val fragmentedMesh = MeshIntelligence(
            totalNodes = 50,
            activeGateways = 2, // Very few gateways
            activeStorageNodes = 5,
            activeComputeNodes = 3,
            networkLoad = 0.9f, // High load due to fragmentation
            storageUtilization = 0.6f,
            computeUtilization = 0.7f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(hubNode, fragmentedMesh)
        
        // Hub node should take on critical infrastructure roles
        assertTrue(plan.addRoles.contains(MeshRole.MESH_ROUTER), "Hub node should be a router")
        assertTrue(plan.addRoles.contains(MeshRole.COORDINATOR), "Hub node should coordinate with many neighbors")
    }

    @Test
    fun testRoleConflictResolution() {
        // Test scenario where node capabilities suggest conflicting role assignments
        val conflictedNode = NodeCapabilitySnapshot(
            nodeId = "conflicted-node",
            resources = ResourceCapabilities(
                availableCPU = 0.5f, // Moderate CPU
                availableRAM = 500_000_000L,
                availableBandwidth = 50_000_000L, // Good bandwidth
                storageOffered = 5_000_000L, // Moderate storage
                batteryLevel = 40,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_MEDIUM,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 40, 
                isCharging = false, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 35,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.WARM, // Slightly warm
            networkQuality = 0.75f, // Good network
            stability = 0.65f // Decent stability
        )
        
        val needyMesh = MeshIntelligence(
            totalNodes = 20,
            activeGateways = 1, // Needs gateways
            activeStorageNodes = 2, // Needs storage
            activeComputeNodes = 1, // Needs compute
            networkLoad = 0.8f,
            storageUtilization = 0.9f,
            computeUtilization = 0.8f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(conflictedNode, needyMesh)
        
        // Should make reasonable trade-offs based on node capabilities and mesh needs
        assertTrue(plan.addRoles.isNotEmpty(), "Should assign at least some roles")
        assertContains(plan.addRoles, MeshRole.MESH_PARTICIPANT, "Should always include participant")
        
        // Should not overload the node with too many resource-intensive roles
        val resourceIntensiveRoles = plan.addRoles.intersect(
            setOf(MeshRole.COMPUTE_NODE, MeshRole.STORAGE_NODE, MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY)
        )
        assertTrue(resourceIntensiveRoles.size <= 2, "Should not assign too many resource-intensive roles")
    }

    // ===== HELPER METHODS =====

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

    private fun createMediumCapabilityNode() = NodeCapabilitySnapshot(
        nodeId = "medium-capability-node",
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

    private fun createDynamicCapabilityNode(iteration: Int) = NodeCapabilitySnapshot(
        nodeId = "dynamic-node-$iteration",
        resources = ResourceCapabilities(
            availableCPU = (iteration % 100) / 100.0f,
            availableRAM = ((iteration % 1000000) * 1000).toLong(),
            availableBandwidth = (iteration % 1000000).toLong(),
            storageOffered = (iteration % 10000000).toLong(),
            batteryLevel = (iteration % 100) + 1,
            thermalThrottling = iteration % 2 == 0,
            powerState = PowerState.values()[iteration % PowerState.values().size],
            networkInterfaces = emptySet()
        ),
        batteryInfo = BatteryInfo(
            level = (iteration % 100) + 1, 
            isCharging = iteration % 2 == 0, 
            estimatedTimeRemaining = null,
            temperatureCelsius = (iteration % 60) + 20,
            health = BatteryHealth.values()[iteration % BatteryHealth.values().size],
            chargingSource = if (iteration % 2 == 0) ChargingSource.USB else null
        ),
        thermalState = ThermalState.values()[iteration % ThermalState.values().size],
        networkQuality = (iteration % 100) / 100.0f,
        stability = ((iteration + 50) % 100) / 100.0f
    )

    private fun createBalancedMeshIntelligence() = MeshIntelligence(
        totalNodes = 50,
        activeGateways = 10,
        activeStorageNodes = 15,
        activeComputeNodes = 12,
        networkLoad = 0.5f,
        storageUtilization = 0.6f,
        computeUtilization = 0.4f
    )

    private fun createDynamicMeshIntelligence(iteration: Int) = MeshIntelligence(
        totalNodes = (iteration % 1000) + 1,
        activeGateways = iteration % 100,
        activeStorageNodes = (iteration + 10) % 200,
        activeComputeNodes = (iteration + 20) % 150,
        networkLoad = (iteration % 100) / 100.0f,
        storageUtilization = ((iteration + 25) % 100) / 100.0f,
        computeUtilization = ((iteration + 50) % 100) / 100.0f
    )
}
