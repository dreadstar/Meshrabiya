package com.ustadmobile.meshrabiya.beta

import android.util.Log

/**
 * Logger for beta testing features.
 */
class BetaTestLogger {
    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val tag = "MeshrabiyaBeta"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }
} 