package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable

/**
 * ServiceEntry
 *
 * Represents a discoverable service in the mesh network, including compute capability metadata.
 * Extends traditional service discovery (host, port, category) with distributed compute capabilities
 * (task types, job types, concurrency limits, resource capacity).
 *
 * Phase 3.2: Service Library Enhancements
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.1
 */
@Serializable
data class ServiceEntry(
    val serviceName: String,
    val host: String,
    val port: Int,
    val category: ServiceCategory,
    
    // Compute capability fields (Phase 3.2)
    val supportsCompute: Boolean = false,
    val taskTypes: List<TaskType> = emptyList(),
    val jobTypes: List<JobType> = emptyList(),
    val maxConcurrentTasks: Int = 1,
    val estimatedCapacity: ResourceMetrics? = null
)

/**
 * ServiceCategory
 *
 * Categorizes services by their primary function.
 */
@Serializable
enum class ServiceCategory {
    COMPUTE,        // Distributed compute execution
    STORAGE,        // Distributed storage
    DISCOVERY,      // Service discovery
    NETWORKING,     // Network infrastructure
    COORDINATION    // Task coordination and scheduling
}

/**
 * ResourceMetrics
 *
 * Estimated resource capacity and usage metrics for a compute node.
 * Used to advertise compute capabilities to the mesh network.
 */
@Serializable
data class ResourceMetrics(
    val ramActualBytes: Long = 0,
    val ramAverageBytes: Long = 0,
    val ramPeakBytes: Long,
    val cpuTimeUsedMs: Long = 0,
    val cpuPercentage: Float = 0f,
    val diskIoOperations: Long = 0,
    val diskStorageUsedBytes: Long
)
