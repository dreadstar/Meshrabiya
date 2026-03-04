package com.ustadmobile.meshrabiya.vnet.datagram

import android.content.Context
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.DatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualNode

/**
 * Thin wrapper required so that we can access the protected constructor specifying the impl class.
 * The [context] parameter is forwarded to [VirtualDatagramSocketImpl] to enable
 * [com.ustadmobile.meshrabiya.vnet.GatewayTypeResolver] instantiation for CLEARNET/TOR routing.
 */
class VirtualDatagramSocket2(
    router: VirtualRouter,
    localVirtualAddress: Int,
    logger: MNetLogger,
    private val parentNode: VirtualNode? = null,
    context: Context? = null,
): DatagramSocket(VirtualDatagramSocketImpl(
    router = router,
    localVirtualAddress = localVirtualAddress,
    logger = logger,
    parentNode = parentNode,
    context = context,
))

