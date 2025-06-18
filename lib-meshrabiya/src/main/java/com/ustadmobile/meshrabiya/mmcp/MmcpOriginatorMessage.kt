package com.ustadmobile.meshrabiya.mmcp

import androidx.room.util.copy
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.vnet.Protocol
import com.ustadmobile.meshrabiya.vnet.VirtualPacket

/**
 * The originator message is used to track routes around the mesh, roughly similar to the BATMAN protocol.
 *
 * @param pingTimeSum the likely sum of the ping time along the journey this message has taken. When
 *                    the message reaches a node, the node at each hop adds to the ping time as it
 *                    is received based on the most recent known ping time of the node that last relayed
 *                    it.
 */
class MmcpOriginatorMessage(
    override val messageId: Int,
    val messageType: Byte,
    val messageData: ByteArray,
    val logger: MNetLogger,
    val sentTime: Long = System.currentTimeMillis(),
    val fitnessScore: Int = 0,
    val nodeRole: Byte = 0,
    val neighborCount: Int = 0,
    val centralityScore: Float = 0f,
    val packedMeshInfo: ByteArray = ByteArray(0),
    what: Byte
) : MmcpMessage(what) {

    fun toVirtualPacket(): VirtualPacket {
        return VirtualPacket(
            protocol = Protocol.MMCP,
            messageType = messageType,
            messageId = messageId,
            data = messageData,
            dataOffset = 0,
            header = null,
            assertHeaderAlreadyInData = false
        )
    }

    override fun toBytes(): ByteArray {
        return messageData
    }

    fun copyWithPingTimeIncrement(pingTime: Long): MmcpOriginatorMessage {
        return MmcpOriginatorMessage(
            messageId = messageId,
            messageType = messageType,
            messageData = messageData,
            logger = logger,
            sentTime = sentTime + pingTime,
            fitnessScore = fitnessScore,
            nodeRole = nodeRole,
            neighborCount = neighborCount,
            centralityScore = centralityScore,
            packedMeshInfo = packedMeshInfo,
            what = what
        )
    }

    companion object {
        
        fun incrementPingTimeSum(virtualPacket: VirtualPacket, pingTime: Short) {
            // TODO: Implement ping time increment logic
        }
        fun fromBytes(byteArray: ByteArray, offset: Int, len: Int): MmcpOriginatorMessage {
            // TODO: Implement proper deserialization
            return MmcpOriginatorMessage(
                messageId = 0,
                messageType = 0,
                messageData = byteArray.copyOfRange(offset, offset + len),
                logger = MNetLoggerStdout(),
                sentTime = 0L,
                fitnessScore = 0,
                nodeRole = 0,
                neighborCount = 0,
                centralityScore = 0.0f,
                packedMeshInfo = ByteArray(0),
                what = 0,
            )
        }
    }
}
