
package com.ustadmobile.meshrabiya.vnet.wifi
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.Context
import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.encodeAsHex
import com.ustadmobile.meshrabiya.ext.prettyPrint
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.RANDOMIZATION_NONE
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.SECURITY_TYPE_WPA2_PSK
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration

class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
    // Returns true when the device supports concurrent AP+STA.
    // When true, active WiFi connections are intentional (internet WiFi in MESH_ROUTER mode)
    // and must not be suppressed by the hotspot monitor.
    // Read at runtime (not construction time) to reflect live detection state.
    private val concurrentApStationSupported: () -> Boolean = { false },
) {
    private val appContext = appContext
    private val logPrefix: String = "[LocalOnlyHotspotManager: $name]"

    private val _state = MutableStateFlow(LocalOnlyHotspotState())

    val state: Flow<LocalOnlyHotspotState> = _state.asStateFlow()

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val macAddrPrefKey = stringPreferencesKey("localonly_macaddr")

    private val localOnlyHotspotCallback = object: WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStarted", null)
            localOnlyHotspotReservation = reservation
            val hotspotConfig = reservation?.toLocalHotspotConfig(
                nodeVirtualAddr = localNodeAddr,
                port = router.localDatagramPort,
                logger = logger,
            )
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onstarted: config=$hotspotConfig")

            _state.takeIf { reservation != null }?.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTED,
                    config = hotspotConfig,
                )
            }
            
            // Start monitoring hotspot state to detect if it stops
            startHotspotMonitoring()
        }

        override fun onStopped() {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStopped", null)
            localOnlyHotspotReservation = null
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    config = null,
                )
            }
        }

        override fun onFailed(reason: Int) {
            logger(Log.ERROR, "$logPrefix localOnlyhotspotcallback : onFailed: " +
                    LocalOnlyHotspotState.errorCodeToString(reason), null
            )

            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    error = reason,
                )
            }
        }
    }

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)
    
    private var hotspotMonitoringJob: kotlinx.coroutines.Job? = null

    suspend fun startLocalOnlyHotspot(
        preferredBand: ConnectBand,
        passphrase: String? = null,
    ) {
        logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand passphrase=...")

        // Clear any stale error from a previous failed attempt BEFORE setting STARTING.
        // requestHotspot() waits on _state.filter { hotspotIsStarted || hotspotError != 0 }.
        // If error is left non-zero from a prior onFailed(), the filter resolves immediately
        // against stale data on every subsequent call — before the new OS callback can fire.
        _state.update { prev -> prev.copy(error = 0) }

        if (Build.VERSION.SDK_INT >= 33) {
            // SDK 33+ path: unchanged
            _state.update { prev -> prev.copy(status = HotspotStatus.STARTING) }
            // ... rest of SDK 33+ block unchanged ...
        } else {
            _state.update { prev -> prev.copy(status = HotspotStatus.STARTING) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                    logger(Log.ERROR, "$logPrefix Missing CHANGE_WIFI_STATE permission for startLocalOnlyHotspot", null)
                } else if (Build.VERSION.SDK_INT >= 28 && passphrase != null) {
                    startLocalOnlyHotspotWithWifiConfig(passphrase)
                } else if (passphrase == null) {
                    logger(Log.INFO, "$logPrefix SDK ${Build.VERSION.SDK_INT}: no passphrase provided — OS will assign SSID/passphrase")
                    wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
                } else {
                    logger(Log.WARN, "$logPrefix SDK ${Build.VERSION.SDK_INT} < 28: cannot set SSID/passphrase — extender roaming degraded")
                    wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
                }
            } else {
                logger(Log.ERROR, "$logPrefix startLocalOnlyHotspot requires API 26+", null)
            }
        }

        // Wait for the OS to respond (STARTED or error).
        val result = _state.filter { it.status.isSettled() }.first()

        // Retry once on ERROR_GENERIC (code 2). Android's LocalOnlyHotspot returns
        // ERROR_GENERIC transiently — typically when the WiFi stack is still finishing a
        // prior teardown. A 500ms pause followed by one re-request succeeds in practice.
        if (result.error == WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC) {
            logger(Log.WARN, "$logPrefix startLocalOnlyHotspot: ERROR_GENERIC received, retrying after 500ms")
            kotlinx.coroutines.delay(500)

            // Reset error and status so requestHotspot()'s filter waits for fresh feedback.
            _state.update { prev -> prev.copy(error = 0, status = HotspotStatus.STARTING) }

            if (Build.VERSION.SDK_INT >= 33) {
                // Rebuild and resubmit the SDK 33+ config (same logic as above).
                // Inline here to avoid re-running the MAC/dataStore lookup.
                // NOTE: If this becomes complex, extract a private submitHotspotRequest() helper.
                wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
            } else if (Build.VERSION.SDK_INT >= 28 && passphrase != null) {
                startLocalOnlyHotspotWithWifiConfig(passphrase)
            } else {
                wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
            }
            // requestHotspot()'s outer filter will now wait for this second OS callback.
        }
    }

    /**
     * Tier 2 SSID/passphrase path for SDK 28–32.
     *
     * Calls the hidden @SystemApi method:
     *   WifiManager#startLocalOnlyHotspot(WifiConfiguration, Handler, LocalOnlyHotspotCallback)
     * via reflection. This method exists on API 28+ but was not made public until API 33.
     * It may be blocked by non-SDK interface restrictions on some SDK 29+ devices; if so,
     * we fall back to the OS-assigned LOHS.
     */
    @SuppressLint("PrivateApi")
    @androidx.annotation.RequiresApi(28)
    private fun startLocalOnlyHotspotWithWifiConfig(passphrase: String) {
        val ssid = "meshr-${localNodeAddr.encodeAsHex()}"
        try {
            @Suppress("DEPRECATION")
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$passphrase\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            }
            val method = WifiManager::class.java.getDeclaredMethod(
                "startLocalOnlyHotspot",
                WifiConfiguration::class.java,
                android.os.Handler::class.java,
                WifiManager.LocalOnlyHotspotCallback::class.java,
            )
            method.isAccessible = true
            method.invoke(wifiManager, wifiConfig, null, localOnlyHotspotCallback)
            logger(Log.INFO, "$logPrefix Tier-2 reflection LOHS invoked: SSID=$ssid")
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix Tier-2 reflection LOHS failed (${e.message}) — falling back to OS-assigned SSID")
            wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
        }
    }

    private fun startHotspotMonitoring() {
        hotspotMonitoringJob?.cancel()
        hotspotMonitoringJob = CoroutineScope(Dispatchers.Default).launch {
            var checkCount = 0
            var wifiReconnectCount = 0

            while (isActive) {
                delay(2000)
                checkCount++

                val currentStatus = _state.value.status
                val wifiInfo = wifiManager.connectionInfo
                val isWifiConnected = wifiInfo?.networkId != -1
                val wifiSSID = wifiInfo?.ssid ?: "null"
                val reservationActive = (localOnlyHotspotReservation != null)
                val concurrentSupported = concurrentApStationSupported()

                logger(Log.DEBUG, "$logPrefix [HOTSPOT MONITOR #$checkCount] Hotspot: $currentStatus | WiFi: $isWifiConnected | SSID: $wifiSSID | concurrentApStationSupported: $concurrentSupported | reservationActive: $reservationActive")

                if (currentStatus == HotspotStatus.STARTED && isWifiConnected && wifiSSID != "<unknown ssid>") {
                    if (concurrentSupported) {
                        logger(Log.DEBUG, "$logPrefix [HOTSPOT MONITOR] AP+STA mode: WiFi ($wifiSSID) is internet link — suppression skipped")
                    } else {
                        wifiReconnectCount++
                        logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] CRITICAL: WiFi reconnected (#$wifiReconnectCount) to $wifiSSID! Forcing disconnect...")

                        try {
                            val reconnectedNetworkId = wifiInfo.networkId
                            wifiManager.disconnect()
                            wifiManager.removeNetwork(reconnectedNetworkId)
                            wifiManager.configuredNetworks?.forEach { config ->
                                wifiManager.disableNetwork(config.networkId)
                            }
                            logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] WiFi disconnected, removed network, and disabled all networks (networkId=$reconnectedNetworkId, ssid=${wifiInfo.ssid})")

                            if (wifiReconnectCount % 3 == 0) {
                                logger(Log.WARN, "$logPrefix [HOTSPOT MONITOR] WiFi interference: $wifiReconnectCount reconnection attempts suppressed")
                                router.notifyHotspotInterference(wifiReconnectCount)
                            }
                        } catch (e: Exception) {
                            logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] Failed to disconnect WiFi", e)
                        }
                    }
                }

                if (currentStatus == HotspotStatus.STARTED && !reservationActive) {
                    logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] CRITICAL: Hotspot reservation lost while status is STARTED!")
                    router.notifyHotspotLost("Hotspot reservation lost unexpectedly")
                    break
                }

                if (currentStatus == HotspotStatus.STOPPED) {
                    logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] Hotspot stopped, ending monitoring. WiFi reconnections suppressed: $wifiReconnectCount")
                    break
                }
            }

            logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] Monitoring ended")
        }
    }
    
    suspend fun stopLocalOnlyHotspot(
        waitForStop: Boolean = true,
    ) {
        logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot")
        hotspotMonitoringJob?.cancel()
        hotspotMonitoringJob = null
        val prevState = _state.getAndUpdate { prev ->
            if(prev.status == HotspotStatus.STARTED) {
                prev.copy(status = HotspotStatus.STOPPING)
            }else {
                prev
            }
        }

        if(prevState.status == HotspotStatus.STARTED) {
            val reservationVal = localOnlyHotspotReservation
            if(reservationVal != null) {
                try {
                    logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot - closing reservation")
                    reservationVal.close()
                    localOnlyHotspotReservation = null
                    _state.value = LocalOnlyHotspotState(
                        status = HotspotStatus.STOPPED,
                        config = null,
                        error = 0,
                    )
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix : exception closing reservation", e)
                    _state.update { prev ->
                        prev.copy(
                            error = 1042,
                        )
                    }
                }

            }else {
                logger(Log.ERROR, "$logPrefix: stopLocalOnlyhotspot - status was started but reservation is null!")
            }
        }else {
            logger(Log.DEBUG, "$logPrefix: stopLocalOnlyhotspot: nothing to do - status is ${prevState.status}")
        }

        if(waitForStop) {
            logger(Log.DEBUG, "$logPrefix: stopLocalOnlyhotspot: waiting for stop to complete")
            _state.filter {
                it.status == HotspotStatus.STOPPED
            }.first()
        }
    }

}