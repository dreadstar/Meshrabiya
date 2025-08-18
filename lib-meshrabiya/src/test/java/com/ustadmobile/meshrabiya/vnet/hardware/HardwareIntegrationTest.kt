package com.ustadmobile.meshrabiya.vnet.hardware

import android.content.Context
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Integration tests for hardware metrics collection with EmergentRoleManager.
 */
class HardwareIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var betaTestLogger: BetaTestLogger
    private lateinit var virtualNode: VirtualNode
    private lateinit var meshRoleManager: MeshRoleManager
    private lateinit var emergentRoleManager: EmergentRoleManager
    private lateinit var mockHardwareManager: MockDeviceCapabilityManager
    
    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        betaTestLogger = mockk(relaxed = true)
        virtualNode = mockk(relaxed = true)
        meshRoleManager = mockk(relaxed = true)
        
        // Mock virtual node basics
        every { virtualNode.addressAsInt } returns 12345
        
        // Mock fitness score for fallback scenarios
        every { meshRoleManager.calculateFitnessScore() } returns FitnessScore(
            signalStrength = 80,
            batteryLevel = 0.75f,
            clientCount = 5
        )
    }
    
    @AfterEach
    fun teardown() {
        if (::emergentRoleManager.isInitialized) {
            emergentRoleManager.stopHardwareMonitoring()
        }
        clearAllMocks()
    }
    
    @Test
    fun `test hardware monitoring lifecycle integration`() = runTest {
        // Given: Any device profile
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.HIGH_END_SMARTPHONE)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Start monitoring
        emergentRoleManager.startHardwareMonitoring()
        
        // Then: Should be monitoring
        assertTrue(emergentRoleManager.isHardwareMonitoring(), "Should be monitoring after start")
        
        // When: Stop monitoring
        emergentRoleManager.stopHardwareMonitoring()
        
        // Then: Should not be monitoring
        assertFalse(emergentRoleManager.isHardwareMonitoring(), "Should not be monitoring after stop")
    }

    @Test
    fun `test capability snapshot includes all required metrics`() = runTest {
        // Given: High-end device
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.HIGH_END_SMARTPHONE)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Get device capabilities
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        
        // Then: Should contain all required metrics
        assertNotNull(capabilities)
        assertEquals("12345", capabilities?.nodeId ?: "") // Should match the mocked virtualNode.addressAsInt
        assertNotNull(capabilities?.batteryInfo)
        assertNotNull(capabilities?.resources)
        assertTrue(capabilities?.timestamp ?: 0 > 0)
        assertTrue(capabilities?.stability ?: 0f > 0)
    }

    @Test
    fun `test high-end device has appropriate capabilities`() = runTest {
        // Given: High-end smartphone profile
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.HIGH_END_SMARTPHONE)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Get device capabilities
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        
        // Then: Should have high capabilities suitable for gateway and storage
        assertNotNull(capabilities)
        assertTrue(capabilities?.resources?.availableCPU ?: 0f > 0.7f, "High-end device should have high CPU availability")
        assertTrue(capabilities?.batteryInfo?.level ?: 0 > 70, "High-end device should have good battery")
        assertEquals(ThermalState.COOL, capabilities?.thermalState, "High-end device should run cool")
        assertTrue(capabilities?.stability ?: 0f > 0.85f, "High-end device should be very stable")
        assertTrue(capabilities?.resources?.availableBandwidth ?: 0L > 50_000_000L, "High-end device should have high bandwidth")
    }

    @Test
    fun `test low-end device has limited capabilities`() = runTest {
        // Given: Low-end device profile
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.LOW_END_SMARTPHONE)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Get device capabilities
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        
        // Then: Should have limited capabilities
        assertNotNull(capabilities)
        assertTrue(capabilities?.resources?.availableCPU ?: 1f < 0.5f, "Low-end device should have limited CPU")
        assertTrue(capabilities?.batteryInfo?.level ?: 100 < 50, "Low-end device should have lower battery")
        assertTrue(capabilities?.stability ?: 1f < 0.7f, "Low-end device should be less stable")
    }

    @Test
    fun `test tablet optimized for storage and relay capabilities`() = runTest {
        // Given: Tablet profile
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.TABLET)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Get device capabilities
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        
        // Then: Should be optimized for storage and relay
        assertNotNull(capabilities)
        assertTrue(capabilities?.resources?.availableRAM ?: 0L > 2_000_000_000L, "Tablet should have high memory")
        assertEquals(ThermalState.COOL, capabilities?.thermalState, "Tablet should run cool")
        assertTrue(capabilities?.stability ?: 0f > 0.8f, "Tablet should be stable")
    }

    @Test
    fun `test emergency thermal protection triggers correctly`() = runTest {
        // Given: Device with critical thermal state
        mockHardwareManager = MockDeviceCapabilityManager(MockDeviceCapabilityManager.DeviceProfile.OVERHEATING_DEVICE)
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager,
            deviceCapabilityManager = mockHardwareManager
        )
        
        // When: Get capabilities
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        
        // Then: Should indicate thermal protection
        assertNotNull(capabilities)
        assertEquals(ThermalState.CRITICAL, capabilities?.thermalState)
        assertTrue(capabilities?.resources?.availableCPU ?: 1f < 0.3f, "Should throttle CPU under thermal stress")
    }
}
