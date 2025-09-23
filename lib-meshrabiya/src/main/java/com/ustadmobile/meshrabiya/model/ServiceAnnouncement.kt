package com.ustadmobile.meshrabiya.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual


@Serializable
data class ServiceAnnouncement(
    val serviceId: String,
    val serviceType: ServiceType,
    val version: String,
    val sizeKB: Int,
    val capabilities: List<String>,
    @Contextual val resourceRequirements: ResourceRequirements,
    @Contextual val executionProfile: ExecutionProfile
) {
    enum class ServiceType {
        PYTHON, JAVA, ML_KIT_NATIVE, ML_KIT_CUSTOM, LITERT, STORAGE, WORKFLOW,
        ML_TEXT_RECOGNITION, ML_OBJECT_DETECTION, ML_TRANSLATION
    }
}


