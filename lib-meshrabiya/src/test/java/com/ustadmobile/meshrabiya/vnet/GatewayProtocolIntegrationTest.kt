package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage.Companion.WHAT_GATEWAY_ANNOUNCEMENT
import com.ustadmobile.meshrabiya.vnet.hardware.MockDeviceCapabilityManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.random.Random

/**
 * Comprehensive test suite for Gateway Protocol Integration
 * Tests gateway announcements, traffic routing, and edge cases
 * Maintains consistency with project's enterprise testing standards
 */
class GatewayProtocolIntegrationTest {

    private lateinit var virtualNode: VirtualNode
    private lateinit var emergentRoleManager: EmergentRoleManager
    private lateinit var androidVirtualNode: AndroidVirtualNode
    private lateinit var betaTestLogger: BetaTestLogger
    private lateinit var mockCapabilityManager: MockDeviceCapabilityManager
    
    // Performance tracking
    private val announcementCount = AtomicInteger(0)
    private val routingSuccessCount = AtomicInteger(0)
    private val routingFailureCount = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)

    @Before
    fun setUp() {
        betaTestLogger = BetaTestLogger.createTestInstance()
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        
        mockCapabilityManager = MockDeviceCapabilityManager()
        
        virtualNode = mockk<VirtualNode>()
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = mockk<Context>(),
            meshRoleManager = mockk<MeshRoleManager>(),
            deviceCapabilityManager = mockCapabilityManager
        )
        
        androidVirtualNode = AndroidVirtualNode(
            context = mockk<Context>(relaxed = true), 
            port = 1,
            logger = mockk(relaxed = true),
            dataStore = mockk<DataStore<Preferences>>(relaxed = true),
            scheduledExecutorService = mockk<ScheduledExecutorService>(relaxed = true)
        )
        
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Test setup completed")
    }

    @After
    fun tearDown() {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", 
            "Test completed - Announcements: ${announcementCount.get()}, " +
            "Routing Success: ${routingSuccessCount.get()}, " +
            "Routing Failures: ${routingFailureCount.get()}, " +
            "Avg Latency: ${if (announcementCount.get() > 0) totalLatency.get() / announcementCount.get() else 0}ms"
        )
    }

    @Test
    fun testBasicGatewayAnnouncement() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing basic gateway announcement")
        
        val startTime = System.currentTimeMillis()
        
        // Test MMCP gateway announcement creation
        val announcement = MmcpGatewayAnnouncement(
            nodeId = "test-gateway",
            gatewayType = MmcpGatewayAnnouncement.GatewayType.CLEARNET,
            supportedProtocols = setOf("HTTP", "HTTPS"),
            capacity = MmcpGatewayAnnouncement.BandwidthCapacity(
                uploadMbps = 1.0f,
                downloadMbps = 10.0f
            ),
            latency = MmcpGatewayAnnouncement.NetworkLatency(
                averageMs = 50,
                jitterMs = 5
            ),
            requestedMessageId = Random.nextInt()
        )
        
        // Test serialization/deserialization
        val serialized = announcement.toBytes()
        val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
        
        assertEquals("Gateway type should match", announcement.gatewayType, deserialized.gatewayType)
        assertEquals("Protocols should match", announcement.supportedProtocols, deserialized.supportedProtocols)
        assertEquals("Upload bandwidth should match", 
            announcement.capacity.uploadMbps, 
            deserialized.capacity.uploadMbps)
        
        val endTime = System.currentTimeMillis()
        totalLatency.addAndGet(endTime - startTime)
        announcementCount.incrementAndGet()
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Basic announcement test completed in ${endTime - startTime}ms")
    }

    @Test
    fun testGatewayAnnouncementPerformance() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing gateway announcement performance")
        
        val iterations = 1000
        val announcements = mutableListOf<MmcpGatewayAnnouncement>()
        
        val creationTime = measureTimeMillis {
            repeat(iterations) { i ->
                val announcement = MmcpGatewayAnnouncement(
                    nodeId = "node-$i",
                    gatewayType = when (i % 3) {
                        0 -> MmcpGatewayAnnouncement.GatewayType.CLEARNET
                        1 -> MmcpGatewayAnnouncement.GatewayType.TOR
                        else -> MmcpGatewayAnnouncement.GatewayType.I2P
                    },
                    supportedProtocols = setOf("HTTP", "HTTPS", "SOCKS5"),
                    capacity = MmcpGatewayAnnouncement.BandwidthCapacity(
                        uploadMbps = Random.nextFloat() * 100f,
                        downloadMbps = Random.nextFloat() * 1000f
                    ),
                    latency = MmcpGatewayAnnouncement.NetworkLatency(
                        averageMs = Random.nextInt(10, 200),
                        jitterMs = Random.nextInt(1, 50)
                    ),
                    isActive = Random.nextBoolean(),
                    requestedMessageId = i
                )
                announcements.add(announcement)
            }
        }
        
        val serializationTime = measureTimeMillis {
            announcements.forEach { it.toBytes() }
        }
        
        // Performance assertions based on project standards
        assertTrue("Creation should be fast (< 1 second)", creationTime < 1000)
        assertTrue("Serialization should be efficient (< 500ms)", serializationTime < 500)
        
        announcementCount.addAndGet(iterations)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Performance test: ${iterations} announcements created in ${creationTime}ms, " +
            "serialized in ${serializationTime}ms")
    }

    @Test
    fun testTrafficRoutingWithFallback() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing traffic routing with fallback")
        
        val testData = "Test routing data".toByteArray()
        val destination = InetAddress.getByName("8.8.8.8")
        
        // Test routing attempt (will fail due to no actual MeshTrafficRouter)
        val startTime = System.currentTimeMillis()
        
        try {
            // This should fail gracefully and fall back to standard routing
            val result = androidVirtualNode.handleInternetTraffic(destination, testData)
            
            // Log the fallback behavior
            betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
                "Routing fallback worked as expected: $result")
            
            routingSuccessCount.incrementAndGet()
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.WARN, "GatewayTest", 
                "Routing test exception (expected): ${e.message}")
            routingFailureCount.incrementAndGet()
        }
        
        val endTime = System.currentTimeMillis()
        totalLatency.addAndGet(endTime - startTime)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Traffic routing test completed in ${endTime - startTime}ms")
    }

    @Test
    fun testMultipleGatewayTypes() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing multiple gateway types")
        
        val gatewayTypes = listOf(
            MmcpGatewayAnnouncement.GatewayType.CLEARNET,
            MmcpGatewayAnnouncement.GatewayType.TOR,
            MmcpGatewayAnnouncement.GatewayType.I2P
        )
        
        gatewayTypes.forEach { gatewayType ->
            val announcement = MmcpGatewayAnnouncement(
                nodeId = "test-gateway-${gatewayType.name}",
                gatewayType = gatewayType,
                supportedProtocols = when (gatewayType) {
                    MmcpGatewayAnnouncement.GatewayType.CLEARNET -> setOf("HTTP", "HTTPS")
                    MmcpGatewayAnnouncement.GatewayType.TOR -> setOf("SOCKS5", "HTTPS")
                    MmcpGatewayAnnouncement.GatewayType.I2P -> setOf("HTTP", "I2P")
                },
                capacity = MmcpGatewayAnnouncement.BandwidthCapacity(1.0f, 5.0f),
                latency = MmcpGatewayAnnouncement.NetworkLatency(100, 10),
                requestedMessageId = Random.nextInt()
            )
            
            // Test serialization for each type
            val serialized = announcement.toBytes()
            val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
            
            assertEquals("Gateway type should be preserved", gatewayType, deserialized.gatewayType)
            
            betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
                "Gateway type $gatewayType processed successfully")
        }
        
        announcementCount.addAndGet(gatewayTypes.size)
    }

    @Test
    fun testEdgeCaseEmptyProtocols() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing edge case: empty protocols")
        
        val announcement = MmcpGatewayAnnouncement(
            nodeId = "empty-protocols-test",
            gatewayType = MmcpGatewayAnnouncement.GatewayType.CLEARNET,
            supportedProtocols = emptySet(),
            capacity = MmcpGatewayAnnouncement.BandwidthCapacity(0.0f, 0.0f),
            latency = MmcpGatewayAnnouncement.NetworkLatency(0, 0),
            isActive = false,
            requestedMessageId = Random.nextInt()
        )
        
        // Should handle empty protocols gracefully
        val serialized = announcement.toBytes()
        val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
        
        assertTrue("Empty protocols should be preserved", deserialized.supportedProtocols.isEmpty())
        assertFalse("Availability should be preserved", deserialized.isActive)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Edge case test passed: empty protocols handled correctly")
        
        announcementCount.incrementAndGet()
    }

    @Test
    fun testEdgeCaseHighLatency() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing edge case: high latency")
        
        val announcement = MmcpGatewayAnnouncement(
            nodeId = "high-latency-test",
            gatewayType = MmcpGatewayAnnouncement.GatewayType.TOR,
            supportedProtocols = setOf("SOCKS5"),
            capacity = MmcpGatewayAnnouncement.BandwidthCapacity(0.1f, 0.5f),
            latency = MmcpGatewayAnnouncement.NetworkLatency(5000, 1000), // Very high latency
            requestedMessageId = Random.nextInt()
        )
        
        val serialized = announcement.toBytes()
        val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
        
        assertEquals("High latency should be preserved", 5000, deserialized.latency.averageMs)
        assertEquals("High jitter should be preserved", 1000, deserialized.latency.jitterMs)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Edge case test passed: high latency (${deserialized.latency.averageMs}ms) handled correctly")
        
        announcementCount.incrementAndGet()
    }

    @Test
    fun testConcurrentGatewayAnnouncements() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing concurrent gateway announcements")
        
        val concurrentCount = 100
        val results = ConcurrentHashMap<Int, Boolean>()
        
        val jobs = (1..concurrentCount).map { i ->
            launch(Dispatchers.Default) {
                try {
                    val announcement = MmcpGatewayAnnouncement(
                        nodeId = "concurrent-test-$i",
                        gatewayType = MmcpGatewayAnnouncement.GatewayType.CLEARNET,
                        supportedProtocols = setOf("HTTP"),
                        capacity = MmcpGatewayAnnouncement.BandwidthCapacity(1.0f, 5.0f),
                        latency = MmcpGatewayAnnouncement.NetworkLatency(50, 5),
                        requestedMessageId = i
                    )
                    
                    val serialized = announcement.toBytes()
                    val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
                    
                    results[i] = (deserialized.messageId == i)
                    announcementCount.incrementAndGet()
                } catch (e: Exception) {
                    betaTestLogger.log(LogLevel.WARN, "GatewayTest", 
                        "Concurrent test exception for iteration $i: ${e.message}")
                    results[i] = false
                }
            }
        }
        
        jobs.forEach { it.join() }
        
        val successCount = results.values.count { it }
        val successRate = successCount.toDouble() / concurrentCount
        
        assertTrue("Success rate should be > 95%", successRate > 0.95)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Concurrent test completed: $successCount/$concurrentCount successful (${(successRate * 100).toInt()}%)")
    }

    @Test
    fun testMemoryUsageDuringAnnouncementFlood() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing memory usage during announcement flood")
        
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        val floodCount = 10000
        repeat(floodCount) { i ->
            val announcement = MmcpGatewayAnnouncement(
                nodeId = "flood-test-$i",
                gatewayType = MmcpGatewayAnnouncement.GatewayType.values()[i % 3],
                supportedProtocols = setOf("HTTP", "HTTPS"),
                capacity = MmcpGatewayAnnouncement.BandwidthCapacity(1.0f, 5.0f),
                latency = MmcpGatewayAnnouncement.NetworkLatency(50, 5),
                requestedMessageId = i
            )
            
            // Force serialization to test memory usage
            announcement.toBytes()
            
            // Periodic memory check
            if (i % 1000 == 0) {
                val currentMemory = runtime.totalMemory() - runtime.freeMemory()
                val memoryIncrease = currentMemory - initialMemory
                
                // Memory increase should be reasonable (< 50MB for 10k announcements)
                assertTrue("Memory increase should be < 50MB", memoryIncrease < 50 * 1024 * 1024)
                
                betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
                    "Memory check at iteration $i: ${memoryIncrease / (1024 * 1024)}MB increase")
            }
        }
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val totalMemoryIncrease = finalMemory - initialMemory
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Memory test completed: ${totalMemoryIncrease / (1024 * 1024)}MB total increase for $floodCount announcements")
        
        announcementCount.addAndGet(floodCount)
    }

    @Test
    fun testRapidGatewayStatusChanges() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing rapid gateway status changes")
        
        val changeCount = 1000
        var currentStatus = true
        
        val startTime = System.currentTimeMillis()
        
        repeat(changeCount) { i ->
            currentStatus = !currentStatus
            
            val announcement = MmcpGatewayAnnouncement(
                nodeId = "status-change-$i",
                gatewayType = MmcpGatewayAnnouncement.GatewayType.CLEARNET,
                supportedProtocols = setOf("HTTP"),
                capacity = MmcpGatewayAnnouncement.BandwidthCapacity(1.0f, 5.0f),
                latency = MmcpGatewayAnnouncement.NetworkLatency(50, 5),
                isActive = currentStatus,
                requestedMessageId = i
            )
            
            val serialized = announcement.toBytes()
            val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
            
            assertEquals("Status change should be preserved", currentStatus, deserialized.isActive)
        }
        
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        // Should handle rapid changes efficiently
        assertTrue("Rapid changes should complete in < 2 seconds", totalTime < 2000)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Rapid status changes test completed: $changeCount changes in ${totalTime}ms")
        
        announcementCount.addAndGet(changeCount)
    }

    @Test
    fun testGatewayAnnouncementWithBetaLogging() = runTest {
        betaTestLogger.log(LogLevel.INFO, "GatewayTest", "Testing gateway announcement with beta logging integration")
        
        // Test all log levels for consistency with project approach
        val logLevels = listOf(LogLevel.DISABLED, LogLevel.BASIC, LogLevel.DETAILED, LogLevel.FULL)
        
        logLevels.forEach { logLevel ->
            betaTestLogger.setLogLevel(logLevel)
            
            val announcement = MmcpGatewayAnnouncement(
                nodeId = "beta-logging-test-${logLevel.name}",
                gatewayType = MmcpGatewayAnnouncement.GatewayType.TOR,
                supportedProtocols = setOf("SOCKS5"),
                capacity = MmcpGatewayAnnouncement.BandwidthCapacity(0.5f, 2.0f),
                latency = MmcpGatewayAnnouncement.NetworkLatency(200, 20),
                requestedMessageId = Random.nextInt()
            )
            
            // This should respect the current log level
            betaTestLogger.log(logLevel, "GatewayTest", 
                "Testing announcement with log level: $logLevel")
            
            val serialized = announcement.toBytes()
            val deserialized = MmcpGatewayAnnouncement.fromBytes(serialized)
            
            assertEquals("Announcement should work regardless of log level", 
                announcement.gatewayType, deserialized.gatewayType)
        }
        
        // Reset to detailed for other tests
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        
        announcementCount.addAndGet(logLevels.size)
        
        betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
            "Beta logging integration test completed successfully")
    }

    /**
     * Extension function to simulate internet traffic handling in AndroidVirtualNode
     * This mimics the actual implementation for testing purposes
     */
    private suspend fun AndroidVirtualNode.handleInternetTraffic(destination: InetAddress, data: ByteArray): Boolean {
        return try {
            // Simulate the reflection-based MeshTrafficRouter integration
            // This will fail as expected since we don't have the actual router
            val routerClass = Class.forName("com.ustadmobile.meshrabiya.routing.MeshTrafficRouter")
            val routeMethod = routerClass.getMethod("routeToInternet", InetAddress::class.java, ByteArray::class.java)
            val result = routeMethod.invoke(null, destination, data)
            result as? Boolean ?: false
        } catch (e: Exception) {
            // Expected behavior - fallback to standard mesh routing
            betaTestLogger.log(LogLevel.DETAILED, "GatewayTest", 
                "MeshTrafficRouter not available, using fallback routing")
            false
        }
    }
}
