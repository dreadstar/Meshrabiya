package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MmcpOriginatorMessageTest {

//    @Test
//    fun givenOriginatorMessage_whenSerializedThenDeserialized_shouldBeEqual() {
//        val sentTime = System.currentTimeMillis()
//        val originatorMessage = MmcpOriginatorMessage(
//            messageId = 1042,
//            pingTimeSum = 200.toShort(),
//            sentTime = sentTime,
//            connectConfig = WifiConnectConfig(
//                nodeVirtualAddr = 1000,
//                ssid = "test",
//                passphrase = "apassword",
//                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
//                port = 1023,
//                hotspotType = HotspotType.WIFIDIRECT_GROUP,
//                persistenceType = HotspotPersistenceType.FULL,
//            )
//        )
//
//        //Apply an offset to ensure this works as expected
//        val originatorBytes = originatorMessage.toBytes()
//        val byteArray = ByteArray(1500)
//        val offset = 42
//        System.arraycopy(originatorBytes, 0, byteArray, offset, originatorBytes.size)
//
//        val messageFromBytes = MmcpOriginatorMessage.fromBytes(byteArray, offset)
//
//        Assert.assertEquals(originatorMessage, messageFromBytes)
//    }

//    @Test
//    fun givenOriginatorMessage_whenConvertedToPacketAndPingTimeIncremented_thenPingTimeShouldMatchExpectedVal() {
//        val originatorMessage = MmcpOriginatorMessage(
//            messageId = 1042,
//            pingTimeSum = 32.toShort(),
//            connectConfig = WifiConnectConfig(
//                nodeVirtualAddr = 1000,
//                ssid = "test",
//                passphrase = "apassword",
//                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
//                port = 1023,
//                hotspotType = HotspotType.WIFIDIRECT_GROUP,
//                persistenceType = HotspotPersistenceType.FULL,
//            )
//        )
//
//        val packet = originatorMessage.toVirtualPacket(
//            toAddr = ADDR_BROADCAST,
//            fromAddr =  1000
//        )
//
//        val pingTimeIncrement = 32.toShort()
//        MmcpOriginatorMessage.incrementPingTimeSum(packet, pingTimeIncrement)
//
//        val messageFromPacket = MmcpMessage.fromVirtualPacket(packet) as MmcpOriginatorMessage
//
//        Assert.assertEquals((originatorMessage.pingTimeSum + pingTimeIncrement).toShort(), messageFromPacket.pingTimeSum)
//        Assert.assertEquals(originatorMessage.connectConfig, messageFromPacket.connectConfig)
//    }
//
//    @Test
//    fun `test packed mesh info encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test setting and getting packed info
//        message.packedMeshInfo = 0x1234567890ABCDEFL
//        assertEquals(0x1234567890ABCDEFL, message.packedMeshInfo)
//    }

//    @Test
//    fun `test neighbor count encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test various neighbor counts
//        val testCounts = listOf(0, 1, 5, 10, 15, 31)
//        for (count in testCounts) {
//            message.neighbors = List(count) { 0 } // Assuming neighbors are integers for this test
//            assertEquals(count, message.neighbors.size)
//        }
//    }

//    @Test
//    fun `test centrality encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test various centrality values
//        val testValues = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
//        for (value in testValues) {
//            message.centrality = value
//            assertEquals(value, message.centrality, 0.01f)
//        }
//    }

//    @Test
//    fun `test signal strength encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test various signal strengths
//        val testStrengths = listOf(-100, -80, -60, -40, -20)
//        for (strength in testStrengths) {
//            message.signalStrength = strength
//            assertEquals(strength, message.signalStrength)
//        }
//    }

//    @Test
//    fun `test battery level encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test various battery levels
//        val testLevels = listOf(0, 25, 50, 75, 100)
//        for (level in testLevels) {
//            message.batteryLevel = level
//            assertEquals(level, message.batteryLevel)
//        }
//    }

//    @Test
//    fun `test internet connectivity encoding and decoding`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test both states
//        message.hasInternetConnectivity = true
//        assertTrue(message.hasInternetConnectivity)
//
//        message.hasInternetConnectivity = false
//        assertFalse(message.hasInternetConnectivity)
//    }

//    @Test
//    fun `test all fields together`() {
//        val message = MmcpOriginatorMessage()
//
//        // Set all fields
//        message.neighbors = List(5) { 0 }
//        message.centrality = 0.75f
//        message.signalStrength = -65
//        message.batteryLevel = 80
//        message.hasInternetConnectivity = true
//
//        // Verify all fields
//        assertEquals(5, message.neighbors.size)
//        assertEquals(0.75f, message.centrality, 0.01f)
//        assertEquals(-65, message.signalStrength)
//        assertEquals(80, message.batteryLevel)
//        assertTrue(message.hasInternetConnectivity)
//    }

//    @Test
//    fun `test field boundaries`() {
//        val message = MmcpOriginatorMessage()
//
//        // Test neighbor count boundaries
//        message.neighbors = List(0) { 0 }
//        assertEquals(0, message.neighbors.size)
//
//        message.neighbors = List(31) { 0 }
//        assertEquals(31, message.neighbors.size)
//
//        // Test centrality boundaries
//        message.centrality = 0.0f
//        assertEquals(0.0f, message.centrality, 0.01f)
//
//        message.centrality = 1.0f
//        assertEquals(1.0f, message.centrality, 0.01f)
//
//        // Test signal strength boundaries
//        message.signalStrength = -100
//        assertEquals(-100, message.signalStrength)
//
//        message.signalStrength = -20
//        assertEquals(-20, message.signalStrength)
//
//        // Test battery level boundaries
//        message.batteryLevel = 0
//        assertEquals(0, message.batteryLevel)
//
//        message.batteryLevel = 100
//        assertEquals(100, message.batteryLevel)
//    }

//    @Test
//    fun `test field independence`() {
//        val message = MmcpOriginatorMessage()
//
//        // Set initial values
//        message.neighbors = List(5) { 0 }
//        message.centrality = 0.5f
//        message.signalStrength = -70
//        message.batteryLevel = 50
//        message.hasInternetConnectivity = true
//
//        // Change one field and verify others remain unchanged
//        message.neighbors = List(10) { 0 }
//
//        assertEquals(10, message.neighbors.size)
//        assertEquals(0.5f, message.centrality, 0.01f)
//        assertEquals(-70, message.signalStrength)
//        assertEquals(50, message.batteryLevel)
//        assertTrue(message.hasInternetConnectivity)
//    }
}