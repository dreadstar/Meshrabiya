package com.ustadmobile.meshrabiya.vnet

data class StorageNodeRequest(
    val requiredSpace: Long,
    val fileName: String,
    val fileId: String? = null,
    val senderId: String = ""
)