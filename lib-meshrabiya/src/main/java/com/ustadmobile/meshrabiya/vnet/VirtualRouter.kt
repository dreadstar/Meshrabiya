package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import java.net.DatagramPacket
import java.net.InetAddress

/**
 * Represents the netwrok
 */
interface VirtualRouter {

    val address: InetAddress

    val networkPrefixLength: Int

    /**
     * Route a given packet. This could be a newly created packet or a packet received by an
     * interface
     *
     * @param packet the packet to route
     * @param receivedFromInterface
     */
    fun route(
        packet: VirtualPacket,
        receivedFromInterface: VirtualNetworkInterface? = null,
    )

    /**
     * When using chain sockets this function will lookup the next hop for the given virtual
     * address.
     */
    fun lookupNextHopForChainSocket(
        address: InetAddress,
        port: Int,
    ): ChainSocketNextHop

    fun nextMmcpMessageId(): Int

    /**
     * Allocate a port on the virtual router
     */
    fun allocateUdpPortOrThrow(
        virtualDatagramSocketImpl: VirtualDatagramSocketImpl,
        portNum: Int
    ): Int

    fun deallocatePort(
        protocol: Protocol,
        portNum: Int
    )


    companion object {



    }

}
