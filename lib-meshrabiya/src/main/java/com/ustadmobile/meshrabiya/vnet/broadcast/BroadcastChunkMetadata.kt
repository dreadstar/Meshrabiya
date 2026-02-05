package com.ustadmobile.meshrabiya.vnet.broadcast

/**
 * Internal metadata for chunk in transit
 * NOT a DTO - internal protocol class for broadcast chunking
 */
data class BroadcastChunkMetadata(
    val chunkId: String,
    val fileId: String,
    val fileName: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val totalFileSize: Long,
    val hash: String
) {
    fun toJson(): String {
        return """{"chunkId":"$chunkId","fileId":"$fileId","fileName":"$fileName","chunkIndex":$chunkIndex,"totalChunks":$totalChunks,"chunkSize":$chunkSize,"totalFileSize":$totalFileSize,"hash":"$hash"}"""
    }
    
    companion object {
        fun fromJson(json: String): BroadcastChunkMetadata {
            // Simple JSON parsing (or use kotlinx.serialization)
            val map = parseSimpleJson(json)
            return BroadcastChunkMetadata(
                chunkId = map["chunkId"] ?: error("Missing chunkId"),
                fileId = map["fileId"] ?: error("Missing fileId"),
                fileName = map["fileName"] ?: error("Missing fileName"),
                chunkIndex = map["chunkIndex"]?.toInt() ?: error("Missing chunkIndex"),
                totalChunks = map["totalChunks"]?.toInt() ?: error("Missing totalChunks"),
                chunkSize = map["chunkSize"]?.toLong() ?: error("Missing chunkSize"),
                totalFileSize = map["totalFileSize"]?.toLong() ?: error("Missing totalFileSize"),
                hash = map["hash"] ?: error("Missing hash")
            )
        }
        
        private fun parseSimpleJson(json: String): Map<String, String> {
            // Simplified parser for our controlled JSON format
            val trimmed = json.trim().removePrefix("{").removeSuffix("}")
            val pairs = mutableMapOf<String, String>()
            
            var key = ""
            var value = ""
            var inQuotes = false
            var isKey = true
            
            for (char in trimmed) {
                when {
                    char == '"' -> inQuotes = !inQuotes
                    char == ':' && !inQuotes -> isKey = false
                    char == ',' && !inQuotes -> {
                        pairs[key.trim()] = value.trim()
                        key = ""
                        value = ""
                        isKey = true
                    }
                    else -> {
                        if (isKey) key += char else value += char
                    }
                }
            }
            
            if (key.isNotEmpty()) {
                pairs[key.trim()] = value.trim()
            }
            
            return pairs
        }
    }
}
