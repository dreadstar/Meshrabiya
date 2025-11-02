package com.ustadmobile.meshrabiya.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Utility to detect if WiFi AP/Station concurrency is available on the device.
 * This checks for Android 11+ and uses WifiManager's isStaApConcurrencySupported() if available.
 */
object WifiConcurrencyUtil {

    /**
     * Returns true if the device supports WiFi AP/Station concurrency.
     */
    fun isApStaConcurrencySupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 11 (API 30) is required for official support
            return false
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        // Official API available from Android 11+
        return try {
            // isStaApConcurrencySupported() is available from API 30
            val method = WifiManager::class.java.getMethod("isStaApConcurrencySupported")
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
}