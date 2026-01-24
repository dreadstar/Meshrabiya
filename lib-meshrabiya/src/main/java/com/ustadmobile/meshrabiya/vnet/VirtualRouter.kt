package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
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
     * Route the given incoming packet.
     *
     * @param packet the packet received
     */
    fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket? = null,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket? = null,
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
     * The default datagram socket local port (not bound to any network). Used to send/receive
     * VirtualPackets over the real network.
     */
    val localDatagramPort: Int


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

    /**
     * Notify that hotspot experienced WiFi interference (WiFi kept reconnecting)
     * @param reconnectionCount Number of times WiFi reconnected during hotspot operation
     */
    fun notifyHotspotInterference(reconnectionCount: Int) {
        // Default implementation: no-op (concrete implementations can override)
    }

    /**
     * Notify that hotspot was lost unexpectedly while mesh was active
     * @param reason Reason why hotspot was lost
     */
    fun notifyHotspotLost(reason: String) {
        // Default implementation: no-op (concrete implementations can override)
    }


    companion object {



    }

}
