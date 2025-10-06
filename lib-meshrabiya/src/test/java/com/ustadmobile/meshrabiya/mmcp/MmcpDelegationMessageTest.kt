package com.ustadmobile.meshrabiya.mmcp

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

class MmcpDelegationMessageTest {

    @Test
    fun testUnsignedAllowed() {
        val json = "{\"type\":\"ResourceRequest\",\"requestId\":\"r1\"}"
        val msg = MmcpDelegationMessage(messageId = 1, jsonPayload = json)

        val bytes = msg.toBytes()
        val parsed = MmcpDelegationMessage.fromBytes(bytes)

        assertEquals(json, parsed.jsonPayload)
        // unsigned messages should be accepted by verify()
        assertTrue(parsed.verify())
    }

    @Test
    fun testSignedVerificationSucceeds() {
        val json = "{\"type\":\"ResourceOffer\",\"requestId\":\"r2\"}"

        // generate Ed25519 keypair
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()

        // sign the payload
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(kp.private)
        signer.update(json.toByteArray())
        val signature = signer.sign()

        val pubEncoded = kp.public.encoded // X.509 encoded public key bytes

        val msg = MmcpDelegationMessage(
            messageId = 2,
            jsonPayload = json,
            signerPublicKey = pubEncoded,
            signature = signature
        )

        val bytes = msg.toBytes()
        val parsed = MmcpDelegationMessage.fromBytes(bytes)

        assertEquals(json, parsed.jsonPayload)
        assertNotNull(parsed.signature)
        assertNotNull(parsed.signerPublicKey)
        assertTrue(parsed.verify())
    }

    @Test
    fun testSignedVerificationFailsWhenTampered() {
        val json = "{\"type\":\"Assignment\",\"assignmentId\":\"a1\"}"

        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(kp.private)
        signer.update(json.toByteArray())
        val signature = signer.sign()

        val pubEncoded = kp.public.encoded

        val msg = MmcpDelegationMessage(
            messageId = 3,
            jsonPayload = json,
            signerPublicKey = pubEncoded,
            signature = signature
        )

        val bytes = msg.toBytes()
        // parse and tamper with payload JSON before verifying
        val parsed = MmcpDelegationMessage.fromBytes(bytes)

        // mutate jsonPayload via reflection-like recreation (simulate tampering by creating a new instance)
        val tampered = MmcpDelegationMessage(
            messageId = parsed.messageId,
            jsonPayload = parsed.jsonPayload + "-tampered",
            timestamp = parsed.timestamp,
            ttl = parsed.ttl,
            hopCount = parsed.hopCount,
            signerPublicKey = parsed.signerPublicKey,
            signature = parsed.signature
        )

        // verification should fail because payload changed
        assertFalse(tampered.verify())
    }
}
