package com.ustadmobile.meshrabiya.model

data class ResourceRequirements(
    val minMemoryMB: Int = 0,
    val minStorageMB: Int = 0,
    val minCpuCores: Int = 1,
    val minGpu: Boolean = false
)
