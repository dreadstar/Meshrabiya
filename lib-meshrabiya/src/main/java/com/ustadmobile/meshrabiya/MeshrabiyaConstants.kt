package com.ustadmobile.meshrabiya

import java.util.UUID

import android.content.Context
import android.content.SharedPreferences

object MeshrabiyaConstants {
    const val LOG_TAG = "Meshrabiya"
    const val VERSION = "0.1d11"
    val UUID_BUSY = UUID(0, 0)

    // --- Settings logic migrated from MeshSettings ---
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mesh_settings", Context.MODE_PRIVATE)
    }

    fun getReplicaCount(): Int {
        return prefs?.getInt("replica_count", 3) ?: 3
    }

    fun setReplicaCount(count: Int) {
        prefs?.edit()?.putInt("replica_count", count)?.apply()
    }

    fun getTimeoutMs(): Long {
        return prefs?.getLong("timeout_ms", 5000L) ?: 5000L
    }

    fun setTimeoutMs(timeout: Long) {
        prefs?.edit()?.putLong("timeout_ms", timeout)?.apply()
    }

    fun getMaxRetries(): Int {
        return prefs?.getInt("max_retries", 3) ?: 3
    }

    fun setMaxRetries(retries: Int) {
        prefs?.edit()?.putInt("max_retries", retries)?.apply()
    }

    fun getChunkSizeKb(): Int = prefs?.getInt("chunk_size_kb", 256) ?: 256
    fun setChunkSizeKb(size: Int) = prefs?.edit()?.putInt("chunk_size_kb", size)?.apply()

    // If true, avoid splitting files into chunks when storing. Default: false
    fun getNoChunking(): Boolean = prefs?.getBoolean("no_chunking", false) ?: false
    fun setNoChunking(noChunking: Boolean) = prefs?.edit()?.putBoolean("no_chunking", noChunking)?.apply()

    private const val DEFAULT_CONNECTION_POOL_SIZE = 8

    fun getConnectionPoolSize(): Int {
        return prefs?.getInt("connection_pool_size", DEFAULT_CONNECTION_POOL_SIZE) ?: DEFAULT_CONNECTION_POOL_SIZE
    }

    fun setConnectionPoolSize(size: Int) {
        prefs?.edit()?.putInt("connection_pool_size", size)?.apply()
    }
}