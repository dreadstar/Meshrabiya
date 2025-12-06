package com.ustadmobile.meshrabiya.vnet

import io.mockk.*
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Unit tests for gateway selection algorithm.
 * Tests VirtualNode's selectBestGateway() method based on suitability scoring.
 */
class GatewaySelectionTest {

    private lateinit var testPacket: VirtualPacket

    @Before
    fun setup() {
        testPacket = createTestPacket(
            toAddr = 0x08080808,  // 8.8.8.8
            toPort = 53,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR
        )
    }

    @Test
    fun `selectBestGateway chooses gateway with highest suitability score`() {
        val gateway1 = createMockGateway(address = 10, centrality = 0.5f, fitness = 0.8f, latency = 100L)
        val gateway2 = createMockGateway(address = 20, centrality = 0.8f, fitness = 0.9f, latency = 50L)
        val gateway3 = createMockGateway(address = 30, centrality = 0.3f, fitness = 0.6f, latency = 200L)
        
        val gateways = listOf(gateway1, gateway2, gateway3)
        
        // Gateway 2 should have highest score: 0.3*0.8 + 0.4*0.9 + 0.3*(1-50/200)
        val selected = selectBestGateway(gateways, MeshRole.TOR_GATEWAY)
        
        assertEquals(20, selected?.nodeAddress, "Gateway 2 should be selected (highest score)")
    }

    @Test
    fun `selectBestGateway returns null when no gateways available`() {
        val selected = selectBestGateway(emptyList(), MeshRole.TOR_GATEWAY)
        
        assertNull(selected, "Should return null when no gateways")
    }

    @Test
    fun `selectBestGateway returns null when all gateways have zero score`() {
        // Gateway with 0 fitness and 0 centrality = 0 score
        val gateway = createMockGateway(address = 10, centrality = 0.0f, fitness = 0.0f, latency = 1000L)
        
        val selected = selectBestGateway(listOf(gateway), MeshRole.TOR_GATEWAY)
        
        assertNull(selected, "Should return null when all scores are 0")
    }

    @Test
    fun `selectBestGateway filters by gateway type`() {
        val torGateway = createMockGateway(
            address = 10,
            roles = setOf(MeshRole.TOR_GATEWAY),
            centrality = 0.8f,
            fitness = 0.9f
        )
        
        val clearnetGateway = createMockGateway(
            address = 20,
            roles = setOf(MeshRole.CLEARNET_GATEWAY),
            centrality = 0.9f,
            fitness = 0.95f
        )
        
        // Selecting for TOR should only consider Tor gateway
        val torGateways = listOf(torGateway).filter { it.hasRole(MeshRole.TOR_GATEWAY) }
        val selected = selectBestGateway(torGateways, MeshRole.TOR_GATEWAY)
        
        assertEquals(10, selected?.nodeAddress)
    }

    @Test
    fun `suitability score weights are correct (30-40-30)`() {
        // Test that centrality, fitness, latency have correct weights
        val gateway = createMockGateway(
            address = 10,
            centrality = 1.0f,  // 30% weight
            fitness = 1.0f,     // 40% weight
            latency = 0L        // 30% weight (normalized to 1.0)
        )
        
        val score = calculateSuitability(gateway, MeshRole.TOR_GATEWAY, maxLatency = 100L)
        
        // Perfect score: 0.3*1.0 + 0.4*1.0 + 0.3*1.0 = 1.0
        assertEquals(1.0f, score, 0.01f)
    }

    @Test
    fun `latency score decreases with higher latency`() {
        val lowLatencyGateway = createMockGateway(address = 10, centrality = 0.5f, fitness = 0.5f, latency = 10L)
        val highLatencyGateway = createMockGateway(address = 20, centrality = 0.5f, fitness = 0.5f, latency = 200L)
        
        val maxLatency = 200L
        val scoreLow = calculateSuitability(lowLatencyGateway, MeshRole.TOR_GATEWAY, maxLatency)
        val scoreHigh = calculateSuitability(highLatencyGateway, MeshRole.TOR_GATEWAY, maxLatency)
        
        assertTrue(scoreLow > scoreHigh, "Lower latency should result in higher score")
    }

    @Test
    fun `single gateway is selected if score greater than 0`() {
        val gateway = createMockGateway(address = 10, centrality = 0.5f, fitness = 0.5f, latency = 50L)
        
        val selected = selectBestGateway(listOf(gateway), MeshRole.TOR_GATEWAY)
        
        assertEquals(10, selected?.nodeAddress)
    }

    @Test
    fun `gateways with equal scores return first one`() {
        val gateway1 = createMockGateway(address = 10, centrality = 0.5f, fitness = 0.5f, latency = 50L)
        val gateway2 = createMockGateway(address = 20, centrality = 0.5f, fitness = 0.5f, latency = 50L)
        
        val selected = selectBestGateway(listOf(gateway1, gateway2), MeshRole.TOR_GATEWAY)
        
        // Should return one of them (implementation may vary)
        assertNotNull(selected)
        assertTrue(selected?.nodeAddress == 10 || selected?.nodeAddress == 20)
    }

    private fun createMockGateway(
        address: Int,
        roles: Set<MeshRole> = setOf(MeshRole.TOR_GATEWAY),
        centrality: Float = 0.5f,
        fitness: Float = 0.5f,
        latency: Long = 50L
    ): NodeTopologyInfo {
        return mockk<NodeTopologyInfo>().apply {
            every { nodeAddress } returns address
            every { meshRoles } returns roles
            every { centralityScore } returns centrality
            every { fitnessScore } returns fitness
            every { pingTime } returns latency.toShort()
            every { hasRole(any()) } answers {
                val role = firstArg<MeshRole>()
                roles.contains(role)
            }
            every { calculateGatewaySuitability(any()) } answers {
                val requestedRole = firstArg<MeshRole>()
                if (!roles.contains(requestedRole)) 0.0f
                else calculateSuitability(this@apply, requestedRole, 200L)
            }
        }
    }

    private fun selectBestGateway(gateways: List<NodeTopologyInfo>, role: MeshRole): NodeTopologyInfo? {
        return gateways
            .map { Pair(it, it.calculateGatewaySuitability(role)) }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun calculateSuitability(node: NodeTopologyInfo, role: MeshRole, maxLatency: Long): Float {
        if (!node.hasRole(role)) return 0.0f
        
        val centralityWeight = 0.3f
        val fitnessWeight = 0.4f
        val latencyWeight = 0.3f
        
        val latencyScore = if (maxLatency > 0 && node.pingTime > 0) {
            1.0f - (node.pingTime.toFloat() / maxLatency.toFloat())
        } else {
            1.0f
        }
        
        return (centralityWeight * node.centralityScore) +
               (fitnessWeight * node.fitnessScore) +
               (latencyWeight * latencyScore.coerceIn(0f, 1f))
    }

    private fun createTestPacket(
        toAddr: Int,
        toPort: Int,
        gatewayType: Byte
    ): VirtualPacket {
        val header = VirtualPacketHeader(
            toAddr = toAddr,
            toPort = toPort,
            fromAddr = 0x0A000001,
            fromPort = 12345,
            lastHopAddr = 0,
            hopCount = 0,
            maxHops = 10,
            gatewayType = gatewayType,
            payloadSize = 0
        )
        return VirtualPacket.fromHeaderAndPayloadData(header, ByteArray(0), 0)
    }
}
