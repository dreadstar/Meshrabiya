package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType


data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val wifiStationState: WifiStationState = WifiStationState(),
    val localOnlyHotspotState: LocalOnlyHotspotState = LocalOnlyHotspotState(),
    val errorCode: Int = 0,
    val concurrentApStationSupported: Boolean = false,
    // True if the device can hold two simultaneous WiFi station (STA) connections.
    // Detected via WifiManager.isStaStaConcurrencySupported() at API 31+.
    // When true, a device in pure station mode (Join Mesh) can simultaneously connect
    // to an internet WiFi network via WifiNetworkSuggestion without dropping the mesh.
    // When false (default), the STA/STA path in connectToInternetWifi() is unavailable.
    val staStaConcurrencySupported: Boolean = false,
    val apCapable: Boolean = false, // new capability flag
) {

    /**
     * The configuration that another device should use to connect to this device (if any)
     */
    val connectConfig: WifiConnectConfig?
        get() = wifiDirectState.config ?: localOnlyHotspotState.config

    val hotspotIsStarting: Boolean
        get() = wifiDirectState.hotspotStatus == HotspotStatus.STARTING
                || localOnlyHotspotState.status == HotspotStatus.STARTING

    val hotspotIsStarted: Boolean
        get() = wifiDirectState.hotspotStatus == HotspotStatus.STARTED
                || localOnlyHotspotState.status == HotspotStatus.STARTED

    fun hotspotError(hotspotType: HotspotType) : Int {
        return when(hotspotType) {
            HotspotType.LOCALONLY_HOTSPOT -> localOnlyHotspotState.error
            HotspotType.WIFIDIRECT_GROUP -> wifiDirectState.error
            HotspotType.AUTO -> 0
        }
    }


    /**
     * Determine the type of hotspot that should be created if a request is made to start one.
     * Currently only WifiDirect group is supported.
     */
    val hotspotTypeToCreate: HotspotType?
    get() {
        return if(connectConfig != null)
            //Hotspot already available- nothing to create
            null
        else if(
            //WifiDirect Group or Local Only hotspot already being created, do nothing
            hotspotIsStarting
        ) {
            null
        } else if(concurrentApStationSupported){
            HotspotType.LOCALONLY_HOTSPOT
        }else {
            HotspotType.WIFIDIRECT_GROUP
        }
    }

}
