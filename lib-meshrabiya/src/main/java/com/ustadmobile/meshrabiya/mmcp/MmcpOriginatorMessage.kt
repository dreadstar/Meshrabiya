package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter

/**
 * The originator message is used to track routes around the mesh, roughly similar to the BATMAN protocol.
 *
 * @param pingTimeSum the likely sum of the ping time along the journey this message has taken. When
 *                    the message reaches a node, the node at each hop adds to the ping time as it
 *                    is received based on the most recent known ping time of the node that last relayed
 *                    it.
 */
class MmcpOriginatorMessage(
    messageId: Int,
    val fitnessScore: Int,
    val nodeRole: Byte,
    val sentTime: Long,
    val neighbors: List<Int> = emptyList(),
    val centralityScore: Float = 0f // new field
) : MmcpMessage(WHAT_ORIGINATOR, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        // Write message header
        dos.writeInt(fitnessScore)
        dos.writeByte(nodeRole.toInt())
        dos.writeLong(sentTime)
        // Write neighbors
        dos.writeInt(neighbors.size)
        neighbors.forEach { dos.writeInt(it) }
        // Write centralityScore
        dos.writeFloat(centralityScore)
        return baos.toByteArray()
    }

    fun copyWithPingTimeIncrement(pingTime: Long): MmcpOriginatorMessage {
        return MmcpOriginatorMessage(
            messageId = messageId,
            fitnessScore = fitnessScore,
            nodeRole = nodeRole,
            sentTime = sentTime + pingTime
        )
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpOriginatorMessage {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            val messageId = buffer.int
            val fitnessScore = buffer.int
            val nodeRole = buffer.get()
            val sentTime = buffer.long
            val neighbors = mutableListOf<Int>()
            var centralityScore = 0f
            if (buffer.remaining() >= 4) {
                val neighborCount = buffer.int
                repeat(neighborCount) {
                    if (buffer.remaining() >= 4) neighbors.add(buffer.int)
                }
                if (buffer.remaining() >= 4) {
                    centralityScore = buffer.float
                }
            }
            return MmcpOriginatorMessage(
                messageId = messageId,
                fitnessScore = fitnessScore,
                nodeRole = nodeRole,
                sentTime = sentTime,
                neighbors = neighbors,
                centralityScore = centralityScore
            )
        }
    }
}