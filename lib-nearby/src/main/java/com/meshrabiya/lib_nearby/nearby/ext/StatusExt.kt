package com.meshrabiya.lib_nearby.nearby.ext

import com.google.android.gms.common.api.Status

fun Status.toPrettyString(): String {
    return "Status (success=$isSuccess code=$statusCode message=$statusMessage)"
}
