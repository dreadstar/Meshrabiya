package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.ustadmobile.meshrabiya.vnet.TestUtils

class MeshRoleManagerTest {
    private lateinit var context: Context
    private lateinit var virtualNode: VirtualNode
    private lateinit var roleManager: MeshRoleManager

    @Before
    fun setup() {
        context = org.mockito.Mockito.mock(Context::class.java)
        virtualNode = object : VirtualNode(port = 12345) {
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 100
            override fun getCurrentNodeRole(): Byte = 1
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() },
                enablePeriodicTasks = false
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        roleManager = MeshRoleManager(virtualNode, context)
    }

    @org.junit.After
    fun teardown() {
        // Ensure we close underlying socket resources created by VirtualNode
        if (::virtualNode.isInitialized) TestUtils.safeClose(virtualNode)
    }

    @Test
    fun testInitialRoleIsMeshNode() {
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithHighFitness() {
        roleManager.userAllowsTorProxy = false
        roleManager.chokePointFlag = false
        roleManager.updateRole()
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithLowFitness() {
        val lowFitnessNode = object : VirtualNode(port = 12345) {
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 10
            override fun getCurrentNodeRole(): Byte = 0
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() },
                enablePeriodicTasks = false
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        try {
            val lowFitnessManager = MeshRoleManager(lowFitnessNode, context)
            lowFitnessManager.updateRole()
            assertEquals(NodeRole.CLIENT, lowFitnessManager.currentRole.value)
        } finally {
            TestUtils.safeClose(lowFitnessNode)
        }
    }

    @Test
    fun testRoleUpdateWithBridgeFitness() {
        val bridgeFitnessNode = object : VirtualNode(port = 12345) {
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 60
            override fun getCurrentNodeRole(): Byte = 2
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() },
                enablePeriodicTasks = false
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        try {
            val bridgeManager = MeshRoleManager(bridgeFitnessNode, context)
            bridgeManager.updateRole()
            assertEquals(NodeRole.BRIDGE, bridgeManager.currentRole.value)
        } finally {
            TestUtils.safeClose(bridgeFitnessNode)
        }
    }

    @Test
    fun testRoleUpdateWithTorProxyAllowed() {
        roleManager.userAllowsTorProxy = true
        roleManager.updateRole()
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testFitnessScoreCalculation() {
        val score = roleManager.calculateFitnessScore()
        assertTrue(score.signalStrength >= 0)
        assertTrue(score.batteryLevel >= 0f)
        assertTrue(score.clientCount >= 0)
    }

    @Test
    fun testCentralityScoreCalculation() {
        val centrality = roleManager.calculateCentralityScore()
        assertTrue(centrality >= 0f)
    }
}
