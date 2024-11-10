package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress

@Deprecated("Should be removed - we are not using addNeighbor at a node level anymore")
fun VirtualNode.connectTo(other: VirtualNode, timeout: Long = 5000) {

    //wait for connections to be ready
    runBlocking {
        withTimeout(timeout) {
            state.filter { it.originatorMessages.containsKey(other.addressAsInt) }
                .first()

            other.state.filter {
                it.originatorMessages.containsKey(addressAsInt)
            }.first()

        }
    }
}