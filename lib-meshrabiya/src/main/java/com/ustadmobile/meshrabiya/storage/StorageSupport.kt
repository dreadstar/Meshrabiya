package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage quota management with Android-aware storage calculations
 */
class StorageQuotaManager(
    private val context: Context,
    private var configuration: StorageConfiguration
) {
    private val directoryQuotas = ConcurrentHashMap<String, Long>()

    fun updateConfiguration(config: StorageParticipationConfig) {
        for (directory in config.allowedDirectories) {
            directoryQuotas[directory] = calculateDirectoryQuota(directory, config.totalQuota)
        }
    }

    fun canStoreFile(fileSize: Long): Boolean {
        val totalUsed = getCurrentUsage()
        val totalQuota = directoryQuotas.values.sum()
        return (totalUsed + fileSize) <= totalQuota
    }

    fun getQuotaInfo(): QuotaInfo {
        val totalQuota = directoryQuotas.values.sum()
        val usedQuota = getCurrentUsage()
        return QuotaInfo(totalQuota, usedQuota)
    }

    private fun calculateDirectoryQuota(directory: String, totalQuota: Long): Long {
        return totalQuota / directoryQuotas.size.coerceAtLeast(1)
    }

    private fun getCurrentUsage(): Long {
        var totalUsed = 0L
        for (directory in directoryQuotas.keys) {
            val dirFile = File(directory)
            totalUsed += getDirectorySize(dirFile)
        }
        return totalUsed
    }

    private fun getDirectorySize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var size = 0L
        dir.walkTopDown().forEach {
            if (it.isFile) size += it.length()
        }
        return size
    }
}

data class QuotaInfo(
    val totalQuota: Long,
    val usedQuota: Long
) {
    val availableQuota: Long get() = totalQuota - usedQuota
    val utilizationPercent: Float get() = if (totalQuota > 0) usedQuota.toFloat() / totalQuota else 0f
}

/**
 * Encryption manager for stored files and session keys
 */
class StorageEncryptionManager {

    private val AES_KEY_SIZE = 32 // 256 bits
    private val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private val IV_SIZE = 16

    // In production, store keys securely (e.g., Android Keystore)
    private val secretKey: SecretKey = generateSecretKey()

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < IV_SIZE) throw IllegalArgumentException("Invalid encrypted data")
        val iv = encryptedData.copyOfRange(0, IV_SIZE)
        val cipherText = encryptedData.copyOfRange(IV_SIZE, encryptedData.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    /**
     * Re-encrypt session keys for a chunk for new recipients.
     * @param sessionKey The raw session key to be encrypted.
     * @param recipientKeyIds List of recipient key IDs.
     * @return Map of recipient key ID to encrypted session key.
     */
    fun reencryptSessionKeysForRecipients(sessionKey: ByteArray, recipientKeyIds: List<Long>): Map<Long, ByteArray> {
        // In production, use each recipient's public key to encrypt the session key.
        // Here, we simulate encryption by using AES with a derived key per recipient.
        return recipientKeyIds.associateWith { keyId ->
            val recipientKey = deriveKeyForRecipient(keyId)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, recipientKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(sessionKey)
            iv + encrypted
        }
    }

    private fun generateSecretKey(): SecretKey {
        val keyBytes = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun deriveKeyForRecipient(keyId: Long): SecretKey {
        // In production, use recipient's public key. Here, derive a key from keyId for demonstration.
        val keyBytes = ByteArray(AES_KEY_SIZE)
        SecureRandom().setSeed(keyId)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }
}

/**
 * Tracks replication health across mesh nodes
 */
class ReplicationTracker {
    private val fileReplications = ConcurrentHashMap<String, List<String>>()
    private val nodeHealth = ConcurrentHashMap<String, Float>()

    fun trackReplication(filePath: String, nodeIds: List<String>) {
        fileReplications[filePath] = nodeIds
    }

    fun getFileHealth(filePath: String): Float {
        val nodes = fileReplications[filePath] ?: return 0f
        val healthyNodes = nodes.count { nodeId ->
            nodeHealth.getOrDefault(nodeId, 1.0f) > 0.5f
        }
        return if (nodes.isNotEmpty()) healthyNodes.toFloat() / nodes.size else 0f
    }

    fun getOverallHealth(): Float {
        if (fileReplications.isEmpty()) return 1.0f
        val totalHealth = fileReplications.keys.sumOf { getFileHealth(it).toDouble() }
        return (totalHealth / fileReplications.size).toFloat()
    }

    fun updateNodeHealth(nodeId: String, health: Float) {
        nodeHealth[nodeId] = health
    }
}