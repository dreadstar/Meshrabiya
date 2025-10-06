package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.mmcp.NodeType
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.mmcp.MmcpNodeAnnouncement
import com.ustadmobile.meshrabiya.mmcp.MmcpHeartbeat
import com.ustadmobile.meshrabiya.mmcp.getCurrentResourceCapabilities
import com.ustadmobile.meshrabiya.mmcp.getCurrentBatteryInfo
import com.ustadmobile.meshrabiya.mmcp.getCurrentThermalState
import com.ustadmobile.meshrabiya.mmcp.calculateFitnessScore

/**
 * Factory class for creating MMCP messages using the new enhanced structure.
 * This replaces the old MmcpOriginatorMessage with comprehensive message types.
 */
object MmcpMessageFactory {
    
    fun createNodeAnnouncement(
        messageId: Int,
        nodeId: String,
        nodeType: NodeType = NodeType.SMARTPHONE,
        fitnessScore: Float? = null,
        centralityScore: Float = 0.0f,
        meshRoles: Set<MeshRole> = setOf(MeshRole.MESH_PARTICIPANT),
        timestamp: Long = System.currentTimeMillis()
    ): MmcpNodeAnnouncement {
        // Get current device capabilities
        val resourceCapabilities = getCurrentResourceCapabilities()
        val batteryInfo = getCurrentBatteryInfo()
        val thermalState = getCurrentThermalState()
        
        // Calculate fitness score if not provided
        val calculatedFitnessScore = fitnessScore ?: calculateFitnessScore(resourceCapabilities)
        
        return MmcpNodeAnnouncement(
            nodeId = nodeId,
            nodeType = nodeType,
            fitnessScore = calculatedFitnessScore.coerceIn(0.0f, 1.0f),
            centralityScore = centralityScore.coerceIn(0.0f, 1.0f),
            meshRoles = meshRoles,
            resourceCapabilities = resourceCapabilities,
            batteryInfo = batteryInfo,
            thermalState = thermalState,
            timestamp = timestamp,

        )
    }
    
    fun createHeartbeat(
        messageId: Int,
        nodeId: String,
        timestamp: Long = System.currentTimeMillis()
    ): MmcpHeartbeat {
        return MmcpHeartbeat(messageId, nodeId, timestamp)
    }
    
    fun createSimpleNodeAnnouncement(
        messageId: Int,
        nodeId: String,
        fitnessScore: Float? = null,
        centralityScore: Float = 0.0f
    ): MmcpNodeAnnouncement {
        return createNodeAnnouncement(
            messageId = messageId,
            nodeId = nodeId,
            fitnessScore = fitnessScore,
            centralityScore = centralityScore
        )
    }

    fun createDelegationMessage(
        messageId: Int,
        jsonPayload: String,
        timestamp: Long = System.currentTimeMillis(),
        ttl: Byte = 7,
        hopCount: Byte = 0,
        signerPublicKey: ByteArray? = null,
        signature: ByteArray? = null,
        // Optional: if you provide a private key (Ed25519) the factory will sign the
        // JSON payload and populate the `signature` field. Prefer providing a
        // `signerKeyPair` so the factory can also attach the corresponding
        // `signerPublicKey` (X.509 encoded) automatically. If only
        // `signerPrivateKey` is provided callers SHOULD also provide
        // `signerPublicKey` so recipients can verify. If no private key is provided,
        // the function preserves the explicit signerPublicKey/signature parameters
        // passed in.
        signerPrivateKey: java.security.PrivateKey? = null,
        // Optional keypair (preferred) - when provided the factory will sign using
        // the private key and set signerPublicKey to the public key's X.509 bytes.
        signerKeyPair: java.security.KeyPair? = null,
    ): MmcpDelegationMessage {
        var finalSignature: ByteArray? = signature
        var finalSignerPub: ByteArray? = signerPublicKey

        try {
            if (signerKeyPair != null) {
                val signer = java.security.Signature.getInstance("Ed25519")
                signer.initSign(signerKeyPair.private)
                signer.update(jsonPayload.toByteArray())
                finalSignature = signer.sign()
                finalSignerPub = signerKeyPair.public.encoded
            } else if (signerPrivateKey != null) {
                val signer = java.security.Signature.getInstance("Ed25519")
                signer.initSign(signerPrivateKey)
                signer.update(jsonPayload.toByteArray())
                finalSignature = signer.sign()
            }
        } catch (e: Exception) {
            // Signing failed - propagate as runtime exception to make caller aware.
            throw RuntimeException("Failed to sign MmcpDelegationMessage", e)
        }

        return MmcpDelegationMessage(
            messageId = messageId,
            jsonPayload = jsonPayload,
            timestamp = timestamp,
            ttl = ttl,
            hopCount = hopCount,
            signerPublicKey = finalSignerPub,
            signature = finalSignature
        )
    }
}
