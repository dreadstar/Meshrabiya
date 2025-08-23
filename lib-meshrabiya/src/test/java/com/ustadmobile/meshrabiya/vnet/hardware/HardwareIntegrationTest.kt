package com.ustadmobile.meshrabiya.vnet.hardware

import org.robolectric.RuntimeEnvironment
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for hardware metrics collection with EmergentRoleManager.
 */
class HardwareIntegrationTest {
    private lateinit var betaTestLogger: BetaTestLogger
    private lateinit var virtualNode: VirtualNode
    private lateinit var meshRoleManager: MeshRoleManager
    private lateinit var emergentRoleManager: EmergentRoleManager
    private lateinit var context: android.app.Application

    @Before
    fun setup() {
    context = RuntimeEnvironment.getApplication() as android.app.Application
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
        // Setup Robolectric shadows for hardware simulation as needed
        // Example: org.robolectric.shadows.ShadowBatteryManager.setBatteryLevel(80)
        // Example: org.robolectric.shadows.ShadowActivityManager.setMemoryInfo(...)
    }
    
    @After
    fun teardown() {
        if (::emergentRoleManager.isInitialized) {
            emergentRoleManager.stopHardwareMonitoring()
        }
        clearAllMocks()
    }
    
    @Test
    fun `test hardware monitoring lifecycle integration`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        emergentRoleManager.startHardwareMonitoring()
        assertTrue("Should be monitoring after start", emergentRoleManager.isHardwareMonitoring())
        emergentRoleManager.stopHardwareMonitoring()
        assertFalse("Should not be monitoring after stop", emergentRoleManager.isHardwareMonitoring())
    }

    @Test
    fun `test capability snapshot includes all required metrics`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        assertNotNull(capabilities)
        assertEquals(12345, capabilities?.nodeId)
        assertNotNull(capabilities?.batteryInfo)
        assertNotNull(capabilities?.resources)
        assertTrue("Timestamp should be > 0", capabilities?.timestamp ?: 0 > 0)
        assertTrue("Stability should be > 0", capabilities?.stability ?: 0f > 0)
    }

    @Test
    fun `test high-end device has appropriate capabilities`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        assertNotNull(capabilities)
        assertTrue("High-end device should have high CPU availability", capabilities?.resources?.availableCPU ?: 0f > 0.7f)
        assertTrue("High-end device should have good battery", capabilities?.batteryInfo?.level ?: 0 > 70)
        assertEquals("High-end device should run cool", ThermalState.COOL, capabilities?.thermalState)
        assertTrue("High-end device should be very stable", capabilities?.stability ?: 0f > 0.85f)
        assertTrue("High-end device should have high bandwidth", capabilities?.resources?.availableBandwidth ?: 0L > 50_000_000L)
    }

    @Test
    fun `test low-end device has limited capabilities`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        assertNotNull(capabilities)
        assertTrue("Low-end device should have limited CPU", capabilities?.resources?.availableCPU ?: 1f < 0.5f)
        assertTrue("Low-end device should have lower battery", capabilities?.batteryInfo?.level ?: 100 < 50)
        assertTrue("Low-end device should be less stable", capabilities?.stability ?: 1f < 0.7f)
    }

    @Test
    fun `test tablet optimized for storage and relay capabilities`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        assertNotNull(capabilities)
        assertTrue("Tablet should have high memory", capabilities?.resources?.availableRAM ?: 0L > 2_000_000_000L)
        assertEquals("Tablet should run cool", ThermalState.COOL, capabilities?.thermalState)
        assertTrue("Tablet should be stable", capabilities?.stability ?: 0f > 0.8f)
    }

    @Test
    fun `test emergency thermal protection triggers correctly`() = runTest {
        // Setup Robolectric shadows for hardware simulation here if needed
        emergentRoleManager = EmergentRoleManager(
            virtualNode = virtualNode,
            context = context,
            meshRoleManager = meshRoleManager
        )
        val capabilities = emergentRoleManager.getDeviceCapabilities()
        assertNotNull(capabilities)
        assertEquals("Should be CRITICAL under thermal stress", ThermalState.CRITICAL, capabilities?.thermalState)
        assertTrue("Should throttle CPU under thermal stress", capabilities?.resources?.availableCPU ?: 1f < 0.3f)
    }
}
