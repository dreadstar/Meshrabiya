package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualNodeBroadcastPacketTypesTest {

    @Test
    fun testBroadcastPacketTypeConstants() {
        assertEquals(0x01, BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK)
        assertEquals(0x02, BroadcastPacketSerializer.TYPE_NACK_REQUEST)
    }

    @Test
    fun testPayloadChecksumConsistency() {
        val payload = BroadcastPacketSerializer.serialize(
            broadcastId = "test-id",
            messageText = "hello",
            chunkMetadata = com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastChunkMetadata(
                chunkId = "0",
                fileId = "",
                fileName = "",
                chunkIndex = 0,
                totalChunks = 0,
                chunkSize = 0L,
                totalFileSize = 0L,
                hash = "",
                latitude = null,
                longitude = null
            ),
            chunkData = ByteArray(0)
        )

        val crc1 = BroadcastPacketSerializer.checksumPayloadCRC32(payload)
        val crc2 = BroadcastPacketSerializer.checksumPayloadCRC32(payload)
        assertEquals(crc1, crc2)
        assertTrue(crc1 != 0L)
    }

}
