package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * MMCP message type used to carry delegation (ResourceRequest/ResourceOffer/Assignment/AssignmentResult)
 * payloads as a JSON string. This replaces ad-hoc use of EmergencyBroadcast for delegation payloads.
 *
 * Added fields:
 * - signerPublicKey: optional X.509 encoded public key bytes
 * - signature: optional signature bytes (Ed25519)
 * - ttl / hopCount: simple hop metadata
 */
class MmcpDelegationMessage(
    messageId: Int,
    val jsonPayload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Byte = 7,
    val hopCount: Byte = 0,
    val signerPublicKey: ByteArray? = null,
    val signature: ByteArray? = null,
) : MmcpMessage(WHAT_DELEGATION_MESSAGE, messageId) {

    override fun toBytes(): ByteArray {
        val payloadBaos = ByteArrayOutputStream()
        val payloadDos = DataOutputStream(payloadBaos)

        val jsonBytes = jsonPayload.toByteArray()
        // write json length and bytes
        payloadDos.writeInt(jsonBytes.size)
        payloadDos.write(jsonBytes)

        // write timestamp
        payloadDos.writeLong(timestamp)

        // ttl / hopcount
        payloadDos.writeByte(ttl.toInt())
        payloadDos.writeByte(hopCount.toInt())

        // signature
        if (signature != null) {
            payloadDos.writeInt(signature.size)
            payloadDos.write(signature)
        } else {
            payloadDos.writeInt(0)
        }

        // signer public key
        if (signerPublicKey != null) {
            payloadDos.writeInt(signerPublicKey.size)
            payloadDos.write(signerPublicKey)
        } else {
            payloadDos.writeInt(0)
        }

    val payload = payloadBaos.toByteArray()
    return MmcpMessage.headerAndPayloadToBytes(header, payload)
    }

    /**
     * Verify the delegation message signature using Ed25519 over the JSON payload bytes.
     * Returns true if signature absent (no verification needed) or if verification succeeds.
     */
    fun verify(): Boolean {
        try {
            if (signature == null || signerPublicKey == null) return true

            val keySpec = X509EncodedKeySpec(signerPublicKey)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(keySpec)

            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(jsonPayload.toByteArray())
            return verifier.verify(signature)
        } catch (e: Exception) {
            // verification failed
            return false
        }
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpDelegationMessage {
            val (header, payload) = MmcpMessage.mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

            val jsonLen = buffer.int
            val jsonBytes = ByteArray(jsonLen)
            buffer.get(jsonBytes)
            val json = String(jsonBytes)

            val timestamp = buffer.long

            val ttl = buffer.get()
            val hopCount = buffer.get()

            val sigLen = buffer.int
            val signature = if (sigLen > 0) {
                val s = ByteArray(sigLen)
                buffer.get(s)
                s
            } else null

            val pubLen = buffer.int
            val signerPub = if (pubLen > 0) {
                val p = ByteArray(pubLen)
                buffer.get(p)
                p
            } else null

            return MmcpDelegationMessage(
                messageId = header.messageId,
                jsonPayload = json,
                timestamp = timestamp,
                ttl = ttl,
                hopCount = hopCount,
                signerPublicKey = signerPub,
                signature = signature
            )
        }
    }
}
