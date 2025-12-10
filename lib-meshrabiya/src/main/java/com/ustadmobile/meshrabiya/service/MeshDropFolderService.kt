package com.ustadmobile.meshrabiya.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Section 7: Drop Folder Service
 * 
 * Monitors a designated drop folder for new files and automatically uploads them to the mesh network.
 * 
 * Features:
 * - FileObserver monitoring for CREATE, MODIFY, CLOSE_WRITE, DELETE, MOVED_TO, MOVED_FROM events
 * - Auto-upload on CLOSE_WRITE (file write completed)
 * - Shared subfolder exception (files in drop/shared/ not re-uploaded)
 * - Auto-generated FileMetadata from file properties
 * - Duplicate prevention
 * - Error handling with retry logic
 * - Foreground service for Android O+
 * 
 * Drop Folder Structure:
 * ```
 * MeshrabiyaFiles/
 *   drop/              <- Monitored folder
 *     file1.txt        <- AUTO-UPLOAD
 *     file2.jpg        <- AUTO-UPLOAD
 *     shared/          <- Exception folder
 *       from_node1.txt <- NO AUTO-UPLOAD (downloaded from other nodes)
 *       from_node2.pdf <- NO AUTO-UPLOAD
 * ```
 */
class MeshDropFolderService : Service() {
    
    private lateinit var dropFolder: File
    private var fileObserver: FileObserver? = null
    private val processedFiles = mutableSetOf<String>()
    private val uploadQueue = ConcurrentLinkedQueue<File>()
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "MeshDropFolderService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mesh_drop_folder"
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024L  // 100 MB
        private const val RETRY_DELAY_NETWORK_ERROR = 30_000L  // 30 seconds
        private const val RETRY_DELAY_SERVICE_ERROR = 5_000L   // 5 seconds
        private const val UPLOAD_RATE_LIMIT_MS = 1000L         // 1 second between uploads
        
        // FileObserver event mask
        private const val ALL_EVENTS = FileObserver.CREATE or 
                                      FileObserver.MODIFY or 
                                      FileObserver.CLOSE_WRITE or 
                                      FileObserver.DELETE or 
                                      FileObserver.MOVED_TO or 
                                      FileObserver.MOVED_FROM
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Start as foreground service for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Initialize drop folder
        dropFolder = File(getExternalFilesDir(null), "MeshrabiyaFiles/drop")
        if (!dropFolder.exists()) {
            dropFolder.mkdirs()
            Log.d(TAG, "Created drop folder: ${dropFolder.absolutePath}")
        }
        
        // Create FileObserver
        initializeFileObserver()
        
        Log.i(TAG, "MeshDropFolderService started, monitoring: ${dropFolder.absolutePath}")
    }
    
    private fun initializeFileObserver() {
        fileObserver = object : FileObserver(dropFolder.absolutePath, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                
                when (event and ALL_EVENTS) {
                    FileObserver.CLOSE_WRITE -> handleFileCompleted(path)
                    FileObserver.CREATE -> handleFileCreated(path)
                    FileObserver.MODIFY -> handleFileModified(path)
                    FileObserver.DELETE -> handleFileDeleted(path)
                    FileObserver.MOVED_TO -> handleFileMovedIn(path)
                    FileObserver.MOVED_FROM -> handleFileMovedOut(path)
                }
            }
        }
        
        fileObserver?.startWatching()
        Log.d(TAG, "FileObserver started")
    }
    
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Drop Folder Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors drop folder for automatic mesh file uploads"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Drop Folder Active")
            .setContentText("Monitoring for new files to upload")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    // ========== Event Handlers ==========
    
    private fun handleFileCreated(path: String) {
        Log.d(TAG, "File created in drop folder: $path")
        // Don't upload yet - wait for CLOSE_WRITE
    }
    
    private fun handleFileModified(path: String) {
        Log.d(TAG, "File modified in drop folder: $path")
        // Remove from processed set to allow re-upload after modification complete
        val file = File(dropFolder, path)
        processedFiles.remove(file.absolutePath)
    }
    
    private fun handleFileCompleted(path: String) {
        val file = File(dropFolder, path)
        
        // Validate file
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            Log.d(TAG, "Skipping invalid file: $path")
            return
        }
        
        // Skip if already processed
        if (processedFiles.contains(file.absolutePath)) {
            Log.d(TAG, "File already processed: $path")
            return
        }
        
        // Skip "shared" subfolder (User Clarification 5)
        if (isInSharedSubfolder(file)) {
            Log.d(TAG, "Skipping file in shared subfolder: $path")
            return
        }
        
        // Check file size limit
        if (file.length() > MAX_FILE_SIZE) {
            Log.w(TAG, "File too large for auto-upload: $path (${file.length()} bytes)")
            showNotification("File too large", "Please upload ${file.name} manually")
            return
        }
        
        Log.i(TAG, "File ready for upload: $path (${file.length()} bytes)")
        
        // Add to upload queue
        uploadQueue.offer(file)
        
        // Process queue on background thread
        uploadExecutor.execute {
            processUploadQueue()
        }
    }
    
    private fun handleFileDeleted(path: String) {
        val file = File(dropFolder, path)
        processedFiles.remove(file.absolutePath)
        Log.d(TAG, "File deleted from drop folder: $path")
    }
    
    private fun handleFileMovedIn(path: String) {
        Log.d(TAG, "File moved into drop folder: $path")
        // Will be uploaded on next CLOSE_WRITE
    }
    
    private fun handleFileMovedOut(path: String) {
        val file = File(dropFolder, path)
        processedFiles.remove(file.absolutePath)
        Log.d(TAG, "File moved out of drop folder: $path")
    }
    
    // ========== Upload Logic ==========
    
    private fun processUploadQueue() {
        while (uploadQueue.isNotEmpty()) {
            val file = uploadQueue.poll() ?: break
            
            // Validate and upload
            if (file.exists() && !isInSharedSubfolder(file) && !processedFiles.contains(file.absolutePath)) {
                uploadToMesh(file)
            }
            
            // Rate limiting: max 1 upload per second
            Thread.sleep(UPLOAD_RATE_LIMIT_MS)
        }
    }
    
    private fun uploadToMesh(file: File) {
        val api = MeshrabiyaApiImpl.getInstance()
        
        Log.i(TAG, "Uploading file to mesh: ${file.name}")
        
        // Upload via MeshrabiyaApi (metadata auto-generated internally)
        api.storeFile(file) { result ->
            result.fold(
                onSuccess = { fileId ->
                    Log.i(TAG, "Drop folder file uploaded successfully: ${file.name} -> $fileId")
                    processedFiles.add(file.absolutePath)
                    
                    // Optional: Delete original file after successful upload
                    if (shouldDeleteAfterUpload()) {
                        file.delete()
                        Log.d(TAG, "Deleted original file after upload: ${file.name}")
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Drop folder upload failed: ${file.name}", error)
                    
                    // Remove from processed set to allow retry
                    processedFiles.remove(file.absolutePath)
                    
                    // Schedule retry based on error type
                    when (error) {
                        is IOException -> {
                            Log.d(TAG, "Network error, scheduling retry in ${RETRY_DELAY_NETWORK_ERROR}ms")
                            scheduleRetry(file, RETRY_DELAY_NETWORK_ERROR)
                        }
                        is IllegalStateException -> {
                            Log.d(TAG, "Service not ready, scheduling retry in ${RETRY_DELAY_SERVICE_ERROR}ms")
                            scheduleRetry(file, RETRY_DELAY_SERVICE_ERROR)
                        }
                        else -> {
                            Log.e(TAG, "Permanent upload failure for ${file.name}, no retry")
                        }
                    }
                }
            )
        }
    }
    
    private fun scheduleRetry(file: File, delayMs: Long) {
        handler.postDelayed({
            if (file.exists()) {
                Log.d(TAG, "Retrying upload: ${file.name}")
                uploadQueue.offer(file)
                uploadExecutor.execute {
                    processUploadQueue()
                }
            }
        }, delayMs)
    }
    
    private fun isInSharedSubfolder(file: File): Boolean {
        var parent = file.parentFile
        
        // Walk up directory tree
        while (parent != null && parent != dropFolder) {
            if (parent.name == "shared") {
                return true  // Skip this file
            }
            parent = parent.parentFile
        }
        
        return false  // Not in shared subfolder
    }
    
    private fun shouldDeleteAfterUpload(): Boolean {
        // Check user preference for auto-delete
        val prefs = getSharedPreferences("meshrabiya", Context.MODE_PRIVATE)
        return prefs.getBoolean("delete_after_upload", false)
    }
    
    private fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    // ========== Service Lifecycle ==========
    
    override fun onBind(intent: Intent?): IBinder? {
        return null  // Not a bound service
    }
    
    override fun onDestroy() {
        fileObserver?.stopWatching()
        uploadExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "MeshDropFolderService stopped")
        super.onDestroy()
    }
}
