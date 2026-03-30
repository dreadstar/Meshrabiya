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

}
