package com.ustadmobile.meshrabiya.vnet

import kotlinx.serialization.Serializable

/**
 * Snapshot of ML-specific capabilities for a mesh node.
 * 
 * Captures device hardware and ML framework availability at a point in time.
 * Used for ML server role assignment and service type distribution.
 */
@Serializable
data class MLCapabilitySnapshot(
    /**
     * Node address (as Int from VirtualNode.addressAsInt)
     */
    val nodeAddress: Int,
    
    /**
     * Total device memory in MB
     */
    val memoryMB: Int,
    
    /**
     * Available storage in MB
     */
    val storageMB: Int,
    
    /**
     * Number of CPU cores
     */
    val cpuCores: Int,
    
    /**
     * ML-specific capabilities (frameworks, accelerators)
     */
    val mlCapabilities: MLCapabilities,
    
    /**
     * Timestamp when snapshot was taken
     */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * ML framework capabilities for ML Kit inference.
 * 
 * Note: LiteRT, GPU acceleration, NNAPI, and LLM support are deferred to future implementation.
 * Currently focuses on ML Kit feature availability for compute task distribution.
 */
@Serializable
data class MLCapabilities(
    /**
     * Available ML Kit features (e.g., "text-recognition", "face-detection", "object-detection", "translation")
     */
    val mlKitFeatures: List<String> = emptyList(),
    
    /**
     * Supports custom ML Kit models (requires >3GB RAM)
     */
    val mlKitCustomSupport: Boolean = false
)

/**
 * ML compute capability advertisement for a node.
 * 
 * Used in compute task lifecycle where requesting nodes select from
 * list of capable responding nodes. No primary/backup assignment - 
 * selection happens at request time based on availability and load.
 */
@Serializable
data class MLComputeCapability(
    /**
     * Node address that can perform ML compute tasks
     */
    val nodeAddress: Int,
    
    /**
     * ML service types this node can handle: "ml-kit-native", "ml-kit-custom"
     */
    val supportedServiceTypes: List<String> = emptyList(),
    
    /**
     * Timestamp of last capability announcement
     */
    val lastAnnouncedAt: Long = System.currentTimeMillis()
)

/**
 * Mesh-wide ML intelligence tracking.
 * 
 * Aggregates ML capabilities across all mesh nodes and tracks service assignments.
 */
@Serializable
data class MLMeshIntelligence(
    /**
     * ML capability snapshots for all known nodes (key: node address)
     */
/**
 * Mesh-wide ML intelligence tracking.
 * 
 * Aggregates ML Kit capabilities across mesh nodes. Compute task lifecycle
 * handles node selection at request time (no pre-assignment of primary/backup servers).
 */
@Serializable
data class MLMeshIntelligence(
    /**
     * ML capability snapshots for all known nodes (key: node address)
     */
    val nodeCapabilities: Map<Int, MLCapabilitySnapshot> = emptyMap(),
    
    /**
     * Total number of ML Kit capable nodes
     */
    val totalMLCapableNodes: Int = 0,
    
    /**
     * Timestamp of last intelligence update
     */
    val timestamp: Long = System.currentTimeMillis()
)