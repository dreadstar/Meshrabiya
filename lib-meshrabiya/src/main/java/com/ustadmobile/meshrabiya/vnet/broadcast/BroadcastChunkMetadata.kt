package com.ustadmobile.meshrabiya.vnet.broadcast
/**
 * Internal metadata for chunk in transit
 */
data class BroadcastChunkMetadata(
    val chunkId: String,
    val fileId: String,
    val fileName: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val totalFileSize: Long,
    val hash: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun toJson(): String {
        // basic fields always present
        var json = """{"chunkId":"$chunkId","fileId":"$fileId","fileName":"$fileName","chunkIndex":$chunkIndex,"totalChunks":$totalChunks,"chunkSize":$chunkSize,"totalFileSize":$totalFileSize,"hash":"$hash"}"""
        // append location only when both coordinates are non‑null
        if (latitude != null && longitude != null) {
            json = json.removeSuffix("}") +
                   ""","latitude":$latitude,"longitude":$longitude}"""
        }
        return json
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
                hash = map["hash"] ?: error("Missing hash"),
                latitude = map["latitude"]?.toDouble(),
                longitude = map["longitude"]?.toDouble()
            )
        }
        
        private fun parseSimpleJson(json: String): Map<String, String> {
            // Simplified parser for our controlled JSON format
            val trimmed = json.trim().removePrefix("{").removeSuffix("}")
            val pairs = trimmed.split(",")
            return pairs.associate { pair ->
                val (key, value) = pair.split(":")
                key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
            }
        }
    }
}