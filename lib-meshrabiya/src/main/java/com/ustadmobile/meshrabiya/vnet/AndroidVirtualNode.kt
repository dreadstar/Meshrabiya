package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.vnet.NodeRole
import com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import java.util.concurrent.ScheduledExecutorService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ScheduledFuture

class AndroidVirtualNode(
    private val context: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    dataStore: DataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
    private val scheduledExecutorService: ScheduledExecutorService
) : VirtualNode(
    port = port,
    logger = logger,
    address = address,
    json = json,
    config = config,
) {

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    /**
     * Listen to the WifiManager for new wifi station connections being established.. When they are
     * established call addNewNeighborConnection to initialize the exchange of originator messages.
     */
    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewNeighborConnection(
            address = it.neighborInetAddress,
            port = it.neighborPort,
            neighborNodeVirtualAddr =  it.neighborVirtualAddress,
            socket = it.socket,
        )
    }

    override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid = MeshrabiyaWifiManagerAndroid(
        appContext = context,
        logger = logger,
        localNodeAddr = addressAsInt,
        router = this,
        chainSocketFactory = chainSocketFactory,
        ioExecutor = connectionExecutor,
        dataStore = dataStore,
        json = json,
        onNewWifiConnectionListener = newWifiConnectionListener,
    )

    private val _bluetoothState = MutableStateFlow(MeshrabiyaBluetoothState())

    private fun updateBluetoothState() {
        try {
            val deviceName = bluetoothAdapter?.name
            _bluetoothState.takeIf { it.value.deviceName != deviceName }?.value =
                MeshrabiyaBluetoothState(deviceName = deviceName)
        }catch(e: SecurityException) {
            logger(Log.WARN, "Could not get device name", e)
        }
    }

    private val bluetoothStateBroadcastReceiver: BroadcastReceiver = object: BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when(state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateBluetoothState()
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothState.value = MeshrabiyaBluetoothState(
                            deviceName = null
                        )
                    }
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

    // Add MeshRoleManager
    val meshRoleManager: MeshRoleManager = MeshRoleManager(this, context)
    
    // Add EmergentRoleManager for advanced role assignment
    val emergentRoleManager: EmergentRoleManager = EmergentRoleManager(this, context, meshRoleManager)

    // Add MeshTrafficRouter for gateway functionality
    private var meshTrafficRouter: Any? = null // Will be initialized when needed

    private var currentWifiState: MeshrabiyaWifiState = MeshrabiyaWifiState()
    private var currentBluetoothState: MeshrabiyaBluetoothState = MeshrabiyaBluetoothState()
    private val _nodeState = MutableStateFlow(LocalNodeState())

    override val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutorService,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentWifiState },
        getFitnessScore = { getCurrentFitnessScore() },
        getNodeRole = { getCurrentNodeRole() }
    )
    
    // Schedule periodic role assessment
    private val roleUpdateFuture = scheduledExecutorService.scheduleAtFixedRate(
        {
            try {
                emergentRoleManager.updateRoles()
            } catch (e: Exception) {
                logger(Log.WARN, "Failed to update roles", e)
            }
        },
        30_000L, // Initial delay: 30 seconds
        60_000L, // Period: 60 seconds
        java.util.concurrent.TimeUnit.MILLISECONDS
    )

    init {
        context.registerReceiver(
            bluetoothStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        receiverRegistered.set(true)

        coroutineScope.launch {
            meshrabiyaWifiManager.state.combine(_bluetoothState) { wifiState, bluetoothState ->
                wifiState to bluetoothState
            }.collect { (wifiState, bluetoothState) ->
                currentWifiState = wifiState
                currentBluetoothState = bluetoothState
                val connectUri = generateConnectLink(
                    hotspot = wifiState.connectConfig,
                    bluetoothConfig = bluetoothState
                ).uri
                _nodeState.update { prev: LocalNodeState ->
                    prev.copy(
                        wifiState = wifiState,
                        bluetoothState = bluetoothState,
                        connectUri = connectUri
                    )
                }
            }
        }
    }

    private fun updateState(
        wifiState: MeshrabiyaWifiState,
        bluetoothState: MeshrabiyaBluetoothState,
        connectUri: String
    ) {
        _nodeState.update { prev: LocalNodeState ->
            prev.copy(
                wifiState = wifiState,
                bluetoothState = bluetoothState,
                connectUri = connectUri
            )
        }
    }

    override fun close() {
        super.close()

        // Cancel role update timer
        roleUpdateFuture.cancel(false)

        if(receiverRegistered.getAndSet(false)) {
            context.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
    }

    suspend fun connectAsStation(
        config: WifiConnectConfig,
    ) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    suspend fun disconnectWifiStation() {
        meshrabiyaWifiManager.disconnectStation()
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
        hotspotType: HotspotType,
    ) : LocalHotspotResponse?{
        updateBluetoothState()
        return super.setWifiHotspotEnabled(enabled, preferredBand, hotspotType)
    }

    suspend fun lookupStoredBssid(ssid: String) : String? {
        return meshrabiyaWifiManager.lookupStoredBssid(ssid)
    }

    /**
     * Store the BSSID for the given SSID. This ensures that when we make subsequent connection
     * attempts we don't need to use the companiondevicemanager again. The BSSID must be provided
     * when reconnecting on Android 10+ if we want to avoid a confirmation dialog.
     */
    fun storeBssid(ssid: String, bssid: String?) {
        logger(Log.DEBUG, "$logPrefix: storeBssid: Store BSSID for $ssid : $bssid")
        if(bssid != null) {
            coroutineScope.launch {
                meshrabiyaWifiManager.storeBssidForAddress(ssid, bssid)
            }
        }else {
            logger(Log.WARN, "$logPrefix : storeBssid: BSSID for $ssid is NULL, can't save to avoid prompts on reconnect")
        }
    }

    override fun getCurrentFitnessScore(): Int {
        return meshRoleManager.calculateFitnessScore().signalStrength
    }

    override fun getCurrentNodeRole(): Byte {
        return meshRoleManager.currentRole.value.ordinal.toByte()
    }

    fun updateWifiState(newState: MeshrabiyaWifiState) {
        _nodeState.update { prev: LocalNodeState ->
            prev.copy(wifiState = newState)
        }
    }

    override fun getMeshRoleManager(): MeshRoleManager = meshRoleManager
    
    /**
     * Initialize mesh traffic router for gateway functionality
     */
    fun initializeMeshTrafficRouter(orbotService: Any?, gatewayCapabilities: Any?) {
        // This would initialize the MeshTrafficRouter when Orbot integration is available
        // For now, we'll just log that it's available
        logger(Log.INFO, "AndroidVirtualNode: MeshTrafficRouter initialization available")
    }
    
    /**
     * Handle gateway traffic routing
     */
    fun handleGatewayTraffic(packet: VirtualPacket): Boolean {
        // Check if this node is acting as a gateway
        val currentRoles = emergentRoleManager.getCurrentMeshRoles()
        val isGateway = currentRoles.any { 
            it == MeshRole.CLEARNET_GATEWAY || it == MeshRole.TOR_GATEWAY 
        }
        
        if (isGateway && isInternetDestination(packet)) {
            logger(Log.DEBUG, "handleGatewayTraffic: Routing packet to ${packet.header.toAddr} via gateway")
            // Route through mesh traffic router
            return routeViaGateway(packet)
        }
        
        return false
    }
    
    /**
     * Check if packet is destined for internet (non-mesh address)
     */
    private fun isInternetDestination(packet: VirtualPacket): Boolean {
        return isInternetDestination(packet.header.toAddr)
    }
    
    /**
     * Route packet via gateway functionality with comprehensive performance tracking
     * Implements enterprise-grade logging consistent with project standards
     */
    private fun routeViaGateway(packet: VirtualPacket): Boolean {
        val startTime = System.currentTimeMillis()
        val packetSize = packet.data.size
        
        try {
            safeLog(LogLevel.DETAILED, "AndroidVNode", 
                "Starting gateway routing",
                mapOf(
                    "destination" to packet.header.toAddr.toString(),
                    "packetSize" to packetSize.toString(),
                    "isInternetDestination" to isInternetDestination(packet).toString()
                )
            )
            
            // Check if this is an internet destination
            if (!isInternetDestination(packet)) {
                safeLog(LogLevel.DETAILED, "AndroidVNode", 
                    "Destination is not internet-bound, skipping gateway routing")
                return false
            }
            
            // Performance tracking for router acquisition
            val routerStartTime = System.currentTimeMillis()
            val meshTrafficRouter = getMeshTrafficRouter()
            val routerAcquisitionTime = System.currentTimeMillis() - routerStartTime
            
            if (meshTrafficRouter != null) {
                safeLog(LogLevel.DETAILED, "AndroidVNode",
                    "MeshTrafficRouter acquired",
                    mapOf(
                        "acquisitionTimeMs" to routerAcquisitionTime.toString(),
                        "routerClass" to meshTrafficRouter.javaClass.simpleName
                    )
                )
                
                // Performance tracking for routing operation
                val routingStartTime = System.currentTimeMillis()
                
                // Use reflection to call routePacket method
                val routeMethod = meshTrafficRouter.javaClass.getMethod("routePacket", VirtualPacket::class.java)
                routeMethod.invoke(meshTrafficRouter, packet)
                
                val routingTime = System.currentTimeMillis() - routingStartTime
                
                val totalTime = System.currentTimeMillis() - startTime
                
                safeLog(LogLevel.INFO, "AndroidVNode", 
                    "Successfully routed packet via gateway",
                    mapOf(
                        "destination" to packet.header.toAddr.toString(),
                        "packetSize" to packetSize.toString(),
                        "routingTimeMs" to routingTime.toString(),
                        "totalTimeMs" to totalTime.toString(),
                        "throughputBps" to (packetSize * 1000 / maxOf(totalTime, 1)).toString()
                    )
                )
                return true
            } else {
                val totalTime = System.currentTimeMillis() - startTime
                
                safeLog(LogLevel.DETAILED, "AndroidVNode", 
                    "MeshTrafficRouter not available, using fallback routing",
                    mapOf(
                        "destination" to packet.header.toAddr.toString(),
                        "packetSize" to packetSize.toString(),
                        "routerAcquisitionTimeMs" to routerAcquisitionTime.toString(),
                        "totalTimeMs" to totalTime.toString(),
                        "fallbackReason" to "RouterNotAvailable"
                    )
                )
                
                // Fallback to standard mesh routing
                return false
            }
            
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            
            safeLog(LogLevel.ERROR, "AndroidVNode", 
                "Gateway routing failed with exception: ${e.message}",
                mapOf(
                    "destination" to packet.header.toAddr.toString(),
                    "packetSize" to packetSize.toString(),
                    "totalTimeMs" to totalTime.toString(),
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "Unknown error")
                ),
                e
            )
            
            // Return false to indicate fallback routing should be used
            return false
        }
    }
    
    /**
     * Get MeshTrafficRouter instance using reflection with comprehensive error handling
     */
    private fun getMeshTrafficRouter(): Any? {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Try multiple possible class locations for MeshTrafficRouter
            val possibleClasses = listOf(
                "org.torproject.android.service.mesh.MeshTrafficRouter",
                "com.ustadmobile.orbotmeshrabiyaintegration.interfaces.MeshTrafficRouter",
                "com.ustadmobile.meshrabiya.routing.MeshTrafficRouter"
            )
            
            for (className in possibleClasses) {
                val routerClass = try {
                    Class.forName(className)
                } catch (e: ClassNotFoundException) {
                    continue
                }
                
                try {
                    // Try to get singleton instance
                    val instanceMethod = routerClass.getMethod("getInstance")
                    val instance = instanceMethod.invoke(null)
                    
                    if (instance != null) {
                        val endTime = System.currentTimeMillis()
                        
                        safeLog(LogLevel.DETAILED, "AndroidVNode",
                            "MeshTrafficRouter acquired via reflection",
                            mapOf(
                                "className" to className,
                                "acquisitionTimeMs" to (endTime - startTime).toString(),
                                "method" to "getInstance"
                            )
                        )
                        
                        return instance
                    }
                    
                } catch (e: NoSuchMethodException) {
                    // Try static field access
                    try {
                        val instanceField = routerClass.getDeclaredField("INSTANCE")
                        instanceField.isAccessible = true
                        val instance = instanceField.get(null)
                        
                        if (instance != null) {
                            val endTime = System.currentTimeMillis()
                            
                            safeLog(LogLevel.DETAILED, "AndroidVNode",
                                "MeshTrafficRouter acquired via field access",
                                mapOf(
                                    "className" to className,
                                    "acquisitionTimeMs" to (endTime - startTime).toString(),
                                    "method" to "fieldAccess"
                                )
                            )
                            
                            return instance
                        }
                        
                    } catch (fieldException: Exception) {
                        safeLog(LogLevel.DETAILED, "AndroidVNode",
                            "Field access failed for $className: ${fieldException.message}")
                    }
                }
            }
            
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.DETAILED, "AndroidVNode",
                "MeshTrafficRouter not found in any expected location",
                mapOf(
                    "searchTimeMs" to (endTime - startTime).toString(),
                    "classesSearched" to possibleClasses.size.toString(),
                    "searchedClasses" to possibleClasses.joinToString(",")
                )
            )
            
            null
            
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.WARN, "AndroidVNode",
                "Failed to acquire MeshTrafficRouter via reflection: ${e.message}",
                mapOf(
                    "searchTimeMs" to (endTime - startTime).toString(),
                    "errorType" to e.javaClass.simpleName
                ),
                e
            )
            
            null
        }
    }
    
    /**
     * Check if address is an internet destination with validation
     */
    private fun isInternetDestination(address: InetAddress): Boolean {
        return try {
            val isInternet = when {
                address.isLoopbackAddress -> {
                    safeLog(LogLevel.DETAILED, "AndroidVNode", "Address is loopback, not internet destination")
                    false
                }
                address.isLinkLocalAddress -> {
                    safeLog(LogLevel.DETAILED, "AndroidVNode", "Address is link-local, not internet destination")
                    false
                }
                address.isSiteLocalAddress -> {
                    safeLog(LogLevel.DETAILED, "AndroidVNode", "Address is site-local, not internet destination")
                    false
                }
                address.isMulticastAddress -> {
                    safeLog(LogLevel.DETAILED, "AndroidVNode", "Address is multicast, not internet destination")
                    false
                }
                else -> {
                    // Check for mesh network addresses (10.255.x.x range)
                    val addrBytes = address.address
                    val isMeshAddress = addrBytes[0] == 10.toByte() && addrBytes[1] == 255.toByte()
                    
                    if (isMeshAddress) {
                        safeLog(LogLevel.DETAILED, "AndroidVNode", "Address is mesh network, not internet destination")
                        false
                    } else {
                        safeLog(LogLevel.DETAILED, "AndroidVNode", "Address appears to be internet destination")
                        true
                    }
                }
            }
            
            safeLog(LogLevel.DETAILED, "AndroidVNode",
                "Internet destination check completed",
                mapOf(
                    "address" to address.hostAddress,
                    "isInternet" to isInternet.toString(),
                    "isLoopback" to address.isLoopbackAddress.toString(),
                    "isLinkLocal" to address.isLinkLocalAddress.toString(),
                    "isSiteLocal" to address.isSiteLocalAddress.toString(),
                    "isMulticast" to address.isMulticastAddress.toString()
                )
            )
            
            isInternet
            
        } catch (e: Exception) {
            safeLog(LogLevel.WARN, "AndroidVNode",
                "Failed to check internet destination: ${e.message}",
                mapOf("address" to address.hostAddress),
                e
            )
            
            // Default to false for safety
            false
        }
    }
    
    /**
     * Check if packet destination is an internet destination 
     */
    private fun isInternetDestination(addr: Int): Boolean {
        // Mesh subnet is 10.255.0.0/16 (0x0AFF0000/16)
        // Convert to bytes and check prefix
        val addrBytes = ByteArray(4)
        addrBytes[0] = (addr shr 24).toByte()
        addrBytes[1] = (addr shr 16).toByte()
        
        return !(addrBytes[0] == 10.toByte() && addrBytes[1] == 255.toByte())
    }
}