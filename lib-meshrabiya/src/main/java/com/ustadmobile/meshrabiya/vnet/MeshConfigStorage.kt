package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores mesh configuration (SSID, password, port) in SharedPreferences for:
 * - Idempotent merge decision (only join if config differs)
 * - Automatic reconnection after app restart
 * - Hotspot recovery with correct credentials
 * 
 * This is the "source of truth" for what mesh the device believes it's part of.
 */
data class MeshConfig(
    val ssid: String,
    val password: String,
    val port: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if this config differs from another (for idempotent join decisions).
     * Ignores timestamp - only compares SSID, password, port.
     */
    fun differFrom(other: MeshConfig?): Boolean {
        if (other == null) return true
        return ssid != other.ssid || password != other.password || port != other.port
    }
}

/**
 * Manages persistent storage of mesh configuration.
 * 
 * Thread-safe: All operations use SharedPreferences which handles synchronization.
 */
class MeshConfigStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mesh_config", 
        Context.MODE_PRIVATE
    )
    
    /**
     * Retrieve stored mesh configuration.
     * 
     * @return MeshConfig if valid config exists, null otherwise
     */
    fun getStoredMeshConfig(): MeshConfig? {
        val ssid = prefs.getString("ssid", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        val port = prefs.getInt("port", 0)
        val timestamp = prefs.getLong("timestamp", 0L)
        
        // Validate port (must be non-zero)
        if (port <= 0) return null
        
        return MeshConfig(ssid, password, port, timestamp)
    }
    
    /**
     * Store new mesh configuration.
     * Overwrites any existing configuration.
     * 
     * @param config New mesh configuration to store
     */
    fun storeNewMeshConfig(config: MeshConfig) {
        prefs.edit()
            .putString("ssid", config.ssid)
            .putString("password", config.password)
            .putInt("port", config.port)
            .putLong("timestamp", config.timestamp)
            .apply()  // Async write
    }
    
    /**
     * Clear stored mesh configuration.
     * Used when leaving mesh or performing factory reset.
     */
    fun clearMeshConfig() {
        prefs.edit()
            .remove("ssid")
            .remove("password")
            .remove("port")
            .remove("timestamp")
            .apply()
    }
}
