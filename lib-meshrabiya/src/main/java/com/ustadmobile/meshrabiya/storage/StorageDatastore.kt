package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.vnet.MeshFile

class MeshDataStoreDb(context: Context) : SQLiteOpenHelper(context, "mesh_datastore.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mesh_files (
                fileId TEXT PRIMARY KEY,
                fileName TEXT,
                fileSize INTEGER,
                storedAt INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mesh_chunks (
                chunkId TEXT PRIMARY KEY,
                fileId TEXT,
                chunkIndex INTEGER,
                totalChunks INTEGER,
                chunkSize INTEGER,
                fileName TEXT,
                relativePath TEXT,
                hash TEXT,
                storedAt INTEGER,
                recipientKeyIds TEXT,
                sessionKeys BLOB
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS mesh_files")
            db.execSQL("DROP TABLE IF EXISTS mesh_chunks")
            onCreate(db)
        }
    }
}

class StorageDataStore private constructor(context: Context) {

    private val dbHelper = MeshDataStoreDb(context)

    companion object {
        @Volatile private var instance: StorageDataStore? = null
        fun getInstance(context: Context): StorageDataStore =
            instance ?: synchronized(this) {
                instance ?: StorageDataStore(context.applicationContext).also { instance = it }
            }
    }

    // --- Mesh File Operations ---
    fun addMeshFile(file: MeshFile) {
        val values = ContentValues().apply {
            put("fileId", file.fileId)
            put("fileName", file.fileName)
            put("fileSize", file.fileSize)
            put("storedAt", file.storedAt)
        }
        dbHelper.writableDatabase.insertWithOnConflict("mesh_files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMeshFile(fileId: String): MeshFile? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_files WHERE fileId = ?", arrayOf(fileId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                MeshFile(
                    fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                    fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("fileSize")),
                    storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt"))
                )
            } else null
        }
    }

    fun deleteMeshFile(fileId: String) {
        dbHelper.writableDatabase.delete("mesh_files", "fileId = ?", arrayOf(fileId))
    }

    fun getAllMeshFiles(): List<MeshFile> {
        val files = mutableListOf<MeshFile>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM mesh_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                files.add(
                    MeshFile(
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                        fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("fileSize")),
                        storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt"))
                    )
                )
            }
        }
        return files
    }

    fun getAllMeshFileNames(): List<String> {
        val names = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT fileName FROM mesh_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                names.add(cursor.getString(cursor.getColumnIndexOrThrow("fileName")))
            }
        }
        return names
    }

    // --- Mesh Chunk Operations ---
    fun addMeshChunk(chunk: MeshChunk) {
        val values = ContentValues().apply {
            put("chunkId", chunk.chunkId)
            put("fileId", chunk.fileId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("chunkSize", chunk.chunkSize)
            put("fileName", chunk.fileName)
            put("relativePath", chunk.relativePath)
            put("hash", chunk.hash)
            put("storedAt", chunk.storedAt)
            put("recipientKeyIds", chunk.recipientKeyIds.joinToString(","))
            put("sessionKeys", serializeSessionKeys(chunk.sessionKeys))
        }
        dbHelper.writableDatabase.insertWithOnConflict("mesh_chunks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMeshChunk(chunkId: String): MeshChunk? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_chunks WHERE chunkId = ?", arrayOf(chunkId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                MeshChunk(
                    chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                    fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                    chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                    totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                    chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                    relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                    hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                    storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                    recipientKeyIds = parseRecipientKeyIds(cursor.getString(cursor.getColumnIndexOrThrow("recipientKeyIds"))),
                    sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                    ownerId = "",
                    ownerPublicKey = ByteArray(0)
                )
            } else null
        }
    }

    fun getChunksForFile(fileId: String): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_chunks WHERE fileId = ? ORDER BY chunkIndex ASC", arrayOf(fileId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chunks.add(
                    MeshChunk(
                        chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                        totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                        chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                        hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                        storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                        recipientKeyIds = parseRecipientKeyIds(cursor.getString(cursor.getColumnIndexOrThrow("recipientKeyIds"))),
                        sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                        ownerId = "",
                        ownerPublicKey = ByteArray(0)
                    )
                )
            }
        }
        return chunks
    }

    fun getAllMeshChunks(): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM mesh_chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                chunks.add(
                    MeshChunk(
                        chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                        totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                        chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                        hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                        storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                        recipientKeyIds = parseRecipientKeyIds(cursor.getString(cursor.getColumnIndexOrThrow("recipientKeyIds"))),
                        sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                        ownerId = "",
                        ownerPublicKey = ByteArray(0)
                    )
                )
            }
        }
        return chunks
    }

    fun getAllMeshFileIds(): List<String> {
        val ids = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT DISTINCT fileId FROM mesh_chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(cursor.getColumnIndexOrThrow("fileId")))
            }
        }
        return ids
    }

    // --- Update chunk metadata/session keys ---
    fun updateMeshChunk(chunk: MeshChunk) {
        val values = ContentValues().apply {
            put("fileId", chunk.fileId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("chunkSize", chunk.chunkSize)
            put("fileName", chunk.fileName)
            put("relativePath", chunk.relativePath)
            put("hash", chunk.hash)
            put("storedAt", chunk.storedAt)
            put("recipientKeyIds", chunk.recipientKeyIds.joinToString(","))
            put("sessionKeys", serializeSessionKeys(chunk.sessionKeys))
        }
        dbHelper.writableDatabase.update("mesh_chunks", values, "chunkId = ?", arrayOf(chunk.chunkId))
    }

    // --- Directory/Index API ---
    fun getDirectoryIndex(): Map<MeshFile, List<MeshChunk>> {
        val files = getAllMeshFiles()
        val index = mutableMapOf<MeshFile, List<MeshChunk>>()
        for (file in files) {
            val chunks = getChunksForFile(file.fileId)
            index[file] = chunks
        }
        return index
    }

    fun getStorageSummary(): StorageSummary {
        val files = getAllMeshFiles()
        val chunks = getAllMeshChunks()
        val totalSize = files.sumOf { it.fileSize }
        return StorageSummary(
            fileCount = files.size,
            chunkCount = chunks.size,
            totalSize = totalSize
        )
    }

    data class StorageSummary(
        val fileCount: Int,
        val chunkCount: Int,
        val totalSize: Long
    )

    // --- Helper methods for recipientKeyIds and sessionKeys serialization ---
    private fun parseRecipientKeyIds(str: String?): List<Long> {
        if (str.isNullOrEmpty()) return emptyList()
        return str.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun serializeSessionKeys(sessionKeys: Map<Long, ByteArray>): ByteArray {
        // Simple serialization: keyId:length:data ... (not for cryptographic use)
        if (sessionKeys.isEmpty()) return ByteArray(0)
        val out = mutableListOf<Byte>()
        for ((keyId, data) in sessionKeys) {
            val keyIdBytes = keyId.toString().toByteArray(Charsets.UTF_8)
            val lengthBytes = data.size.toString().toByteArray(Charsets.UTF_8)
            out.add(keyIdBytes.size.toByte())
            out.addAll(keyIdBytes.toList())
            out.add(lengthBytes.size.toByte())
            out.addAll(lengthBytes.toList())
            out.addAll(data.toList())
        }
        return out.toByteArray()
    }

    private fun deserializeSessionKeys(blob: ByteArray?): Map<Long, ByteArray> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        val map = mutableMapOf<Long, ByteArray>()
        var idx = 0
        while (idx < blob.size) {
            val keyIdLen = blob[idx].toInt()
            idx++
            val keyId = String(blob, idx, keyIdLen, Charsets.UTF_8).toLongOrNull()
            idx += keyIdLen
            val lenLen = blob[idx].toInt()
            idx++
            val dataLen = String(blob, idx, lenLen, Charsets.UTF_8).toIntOrNull() ?: 0
            idx += lenLen
            val data = blob.copyOfRange(idx, idx + dataLen)
            idx += dataLen
            if (keyId != null) map[keyId] = data
        }
        return map
    }
}