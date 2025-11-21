package com.ustadmobile.meshrabiya.service.compute

// Code bundle signature verification and hash calculation
import java.security.MessageDigest

object CodeVerification {
    fun verifyCodeBundle(code: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // Verify code signature using public key
        // (Stub: Replace with actual cryptographic verification)
        return MessageDigest.isEqual(signature, MessageDigest.getInstance("SHA-256").digest(code))
    }
    fun calculateHash(code: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
