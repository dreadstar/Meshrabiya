package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MeshRoleManagerTest {
    private lateinit var context: Context
    private lateinit var virtualNode: VirtualNode
    private lateinit var roleManager: MeshRoleManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        virtualNode = mockk(relaxed = true)
        
        // Mock VirtualNode methods
        every { virtualNode.addressAsInt } returns 12345
        every { virtualNode.neighbors() } returns emptyList()
        every { virtualNode.getOriginatingMessageManager() } returns mockk(relaxed = true)
        
        roleManager = MeshRoleManager(virtualNode, context)
    }

    @Test
    fun `test initial role is mesh node`() {
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun `test role update with good fitness score`() {
        // Mock good fitness score
        every { virtualNode.neighbors() } returns List(5) { mockk() }
        
        // Mock topology map for centrality calculation
        val mockTopology = mapOf(12345 to setOf(1, 2, 3, 4, 5))
        val mockOriginatingMessageManager = mockk<OriginatingMessageManager>()
        every { mockOriginatingMessageManager.getTopologyMap() } returns mockTopology
        every { virtualNode.getOriginatingMessageManager() } returns mockOriginatingMessageManager
        
        roleManager.updateRole()
        
        // Should remain MESH_NODE with good metrics
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun `test role update with poor fitness score`() {
        // Mock poor fitness score
        every { virtualNode.neighbors() } returns emptyList()
        
        // Mock empty topology map
        val mockOriginatingMessageManager = mockk<OriginatingMessageManager>()
        every { mockOriginatingMessageManager.getTopologyMap() } returns emptyMap()
        every { virtualNode.getOriginatingMessageManager() } returns mockOriginatingMessageManager
        
        roleManager.updateRole()
        
        // Should become CLIENT with poor metrics
        assertEquals(NodeRole.CLIENT, roleManager.currentRole.value)
    }

    @Test
    fun `test role update with bridge fitness score`() {
        // Mock moderate fitness score
        every { virtualNode.neighbors() } returns List(3) { mockk() }
        
        // Mock moderate topology map
        val mockTopology = mapOf(12345 to setOf(1, 2, 3))
        val mockOriginatingMessageManager = mockk<OriginatingMessageManager>()
        every { mockOriginatingMessageManager.getTopologyMap() } returns mockTopology
        every { virtualNode.getOriginatingMessageManager() } returns mockOriginatingMessageManager
        
        roleManager.updateRole()
        
        // Should become BRIDGE with moderate metrics
        assertEquals(NodeRole.BRIDGE, roleManager.currentRole.value)
    }

    @Test
    fun `test role update with user allows tor proxy`() {
        roleManager.userAllowsTorProxy = true
        
        // Mock poor fitness score
        every { virtualNode.neighbors() } returns emptyList()
        
        roleManager.updateRole()
        
        // Should remain MESH_NODE when user allows Tor proxy
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun `test role update with choke point flag`() {
        // Mock topology that creates choke point
        val mockTopology = mapOf(
            12345 to setOf(1, 2), // Only 2 neighbors - choke point
            1 to setOf(12345),
            2 to setOf(12345)
        )
        val mockOriginatingMessageManager = mockk<OriginatingMessageManager>()
        every { mockOriginatingMessageManager.getTopologyMap() } returns mockTopology
        every { virtualNode.getOriginatingMessageManager() } returns mockOriginatingMessageManager
        
        roleManager.updateRole()
        
        // Should remain MESH_NODE when choke point detected
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
        assertTrue(roleManager.chokePointFlag)
    }

    @Test
    fun `test fitness score calculation`() {
        val fitnessScore = roleManager.calculateFitnessScore()
        
        // Default values should be present
        assertEquals(0, fitnessScore.signalStrength) // No wifi/bluetooth connection
        assertEquals(0.5f, fitnessScore.batteryLevel) // Default value
        assertEquals(0, fitnessScore.clientCount) // No neighbors
    }

    @Test
    fun `test centrality score calculation`() {
        // Mock topology for centrality calculation
        val mockTopology = mapOf(
            12345 to setOf(1, 2, 3),
            1 to setOf(12345, 4),
            2 to setOf(12345, 5),
            3 to setOf(12345, 6),
            4 to setOf(1),
            5 to setOf(2),
            6 to setOf(3)
        )
        val mockOriginatingMessageManager = mockk<OriginatingMessageManager>()
        every { mockOriginatingMessageManager.getTopologyMap() } returns mockTopology
        every { virtualNode.getOriginatingMessageManager() } returns mockOriginatingMessageManager
        
        val centralityScore = roleManager.calculateCentralityScore()
        
        // Should calculate some centrality score
        assertTrue(centralityScore > 0f)
        assertEquals(centralityScore, roleManager.centralityScore)
    }
} 