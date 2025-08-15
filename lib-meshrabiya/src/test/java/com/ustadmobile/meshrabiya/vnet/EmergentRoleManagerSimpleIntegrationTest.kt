package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Simple integration tests for EmergentRoleManager
 */
class EmergentRoleManagerSimpleIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var virtualNode1: VirtualNode
    private lateinit var virtualNode2: VirtualNode
    private lateinit var meshRoleManager1: MeshRoleManager
    private lateinit var meshRoleManager2: MeshRoleManager
    private lateinit var emergentRoleManager1: EmergentRoleManager
    private lateinit var emergentRoleManager2: EmergentRoleManager

    @Before
    fun setup() {
        context = mock(Context::class.java)
        
        virtualNode1 = mock(VirtualNode::class.java)
        virtualNode2 = mock(VirtualNode::class.java)
        
        `when`(virtualNode1.addressAsInt).thenReturn(12345)
        `when`(virtualNode2.addressAsInt).thenReturn(12346)
        `when`(virtualNode1.address).thenReturn(java.net.InetAddress.getLoopbackAddress())
        `when`(virtualNode2.address).thenReturn(java.net.InetAddress.getLoopbackAddress())
        
        meshRoleManager1 = mock(MeshRoleManager::class.java)
        meshRoleManager2 = mock(MeshRoleManager::class.java)
        
        `when`(meshRoleManager1.userAllowsTorProxy).thenReturn(true)
        `when`(meshRoleManager2.userAllowsTorProxy).thenReturn(true)
        
        `when`(meshRoleManager1.calculateFitnessScore()).thenReturn(
            FitnessScore(signalStrength = 95, batteryLevel = 0.9f, clientCount = 3)
        )
        `when`(meshRoleManager2.calculateFitnessScore()).thenReturn(
            FitnessScore(signalStrength = 70, batteryLevel = 0.6f, clientCount = 1)
        )
        
        emergentRoleManager1 = EmergentRoleManager(virtualNode1, context, meshRoleManager1)
        emergentRoleManager2 = EmergentRoleManager(virtualNode2, context, meshRoleManager2)
    }

    @Test
    fun testMultiNodeCreation() {
        assertNotNull("EmergentRoleManager1 should be created", emergentRoleManager1)
        assertNotNull("EmergentRoleManager2 should be created", emergentRoleManager2)
    }

    @Test
    fun testNodeAnnouncements() {
        // Node 1 announces its roles to node 2
        val node1Roles = emergentRoleManager1.getCurrentMeshRoles()
        emergentRoleManager2.processNodeAnnouncement("node-1", node1Roles)
        
        // This should not throw an exception
        val intelligence = emergentRoleManager2.getMeshIntelligence()
        assertNotNull("Mesh intelligence should be updated", intelligence)
    }

    @Test
    fun testRoleCoordination() {
        // Both nodes update their roles
        emergentRoleManager1.updateRoles()
        emergentRoleManager2.updateRoles()
        
        val roles1 = emergentRoleManager1.getCurrentMeshRoles()
        val roles2 = emergentRoleManager2.getCurrentMeshRoles()
        
        assertNotNull("Node 1 should have roles", roles1)
        assertNotNull("Node 2 should have roles", roles2)
    }
}
