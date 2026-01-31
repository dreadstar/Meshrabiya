package com.ustadmobile.meshrabiya.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FileLogger writes all mesh logs to a file for debugging
 */
class FileLogger(context: Context) {
    
    private val logFile: File = File(context.getExternalFilesDir(null), "meshrabiya_debug.log")
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    
    init {
        // Clear old log on startup (keep last 100KB if file is too large)
        if (logFile.exists() && logFile.length() > 1024 * 1024) { // 1MB
            val lines = logFile.readLines()
            logFile.writeText(lines.takeLast(1000).joinToString("\n") + "\n")
        }
        
        log(Log.INFO, "FileLogger", "=== MESH DEBUG LOG STARTED ===")
        log(Log.INFO, "FileLogger", "Log file: ${logFile.absolutePath}")
    }
    
    @Synchronized
    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val timestamp = dateFormat.format(Date())
            val priorityChar = when (priority) {
                Log.VERBOSE -> 'V'
                Log.DEBUG -> 'D'
                Log.INFO -> 'I'
                Log.WARN -> 'W'
                Log.ERROR -> 'E'
                else -> '?'
            }
            
            val logLine = "$timestamp $priorityChar/$tag: $message"
            
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(logLine)
                
                if (throwable != null) {
                    PrintWriter(writer).use { pw ->
                        throwable.printStackTrace(pw)
                    }
                }
            }
            
            // Also log to logcat
            when (priority) {
                Log.VERBOSE -> Log.v(tag, message, throwable)
                Log.DEBUG -> Log.d(tag, message, throwable)
                Log.INFO -> Log.i(tag, message, throwable)
                Log.WARN -> Log.w(tag, message, throwable)
                Log.ERROR -> Log.e(tag, message, throwable)
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to write log", e)
        }
    }
    
    fun getLogFilePath(): String = logFile.absolutePath
    
    fun clearLog() {
        logFile.writeText("")
        log(Log.INFO, "FileLogger", "=== LOG CLEARED ===")
    }
}
