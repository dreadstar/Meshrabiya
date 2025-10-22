package com.ustadmobile.meshrabiya.storage

import com.ustadmobile.meshrabiya.meshgossip.MeshGossipService
import com.ustadmobile.meshrabiya.meshgossip.model.StorageNodeRequest
import com.ustadmobile.meshrabiya.settings.AppSettings


class ReplicationManager(private val meshGossipService: MeshGossipService) {
    // --- New code: Replication initiation ---
    fun replicateFile(fileId: String, file: java.io.File) {
        val desiredReplicas = AppSettings.getReplicaCount()
        val currentReplicas = queryReplicaCount(fileId)
        if (currentReplicas >= desiredReplicas) return

        val request = StorageNodeRequest(file.length(), file.name, fileId)
        meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { candidates ->
            val replicasNeeded = desiredReplicas - currentReplicas
            val selectedNodes = candidates
                .filter { it.availableSpace >= file.length() }
                .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
                .take(replicasNeeded)

            selectedNodes.forEach { node ->
                meshGossipService.sendFile(node.url, file) { result ->
                    // Optionally handle completion
                }
            }
        }
    }

    // --- New code: Query replica count ---
    fun queryReplicaCount(fileId: String): Int {
        // Broadcast query for fileId, count responses
        // ...implementation...
        return 0 // stub
    }
}