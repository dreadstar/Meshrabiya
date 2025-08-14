package com.ustadmobile.meshrabiya.mmcp

import org.junit.Test
import org.junit.Assert.*
import java.util.*

class EnhancedGossipMessageTest {

    @Test
    fun testCreateNodeAnnouncement() {
        val message = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node-1",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(
                manufacturer = "Test Manufacturer",
                model = "Test Model",
                androidVersion = "13",
                apiLevel = 33,
                totalRAM = 8 * 1024 * 1024 * 1024L, // 8GB
                availableStorage = 128 * 1024 * 1024 * 1024L, // 128GB
                cpuCores = 8,
                cpuArchitecture = "arm64",
                hasGPS = true,
                hasCellular = true
            ),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT, MeshRole.STORAGE_NODE),
            fitnessScore = 0.85f,
            centralityScore = 0.72f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.75f,
                availableRAM = 4 * 1024 * 1024 * 1024L, // 4GB
                availableBandwidth = 100 * 1024 * 1024L, // 100MB/s
                storageOffered = 64 * 1024 * 1024 * 1024L, // 64GB
                batteryLevel = 85,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = setOf(NetworkInterface("wlan0", "WiFi"))
            ),
            batteryInfo = BatteryInfo(
                level = 85,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 28,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL
        )

        assertEquals("test-node-1", message.sourceNodeId)
        assertEquals("test-node-1", message.originatorNodeId)
        assertEquals(GossipMessageType.NODE_ANNOUNCEMENT, message.messageType)
        assertEquals(MessagePriority.NORMAL, message.priority)
        assertEquals(0, message.hopCount)
        assertEquals(7, message.ttl)
        
        val payload = message.payload as NodeStatePayload
        assertEquals("test-node-1", payload.nodeId)
        assertEquals(NodeType.SMARTPHONE, payload.nodeType)
        assertEquals(0.85f, payload.fitnessScore, 0.01f)
        assertEquals(0.72f, payload.centralityScore, 0.01f)
        assertEquals(2, payload.meshRoles.size)
        assertTrue(payload.meshRoles.contains(MeshRole.MESH_PARTICIPANT))
        assertTrue(payload.meshRoles.contains(MeshRole.STORAGE_NODE))
    }

    @Test
    fun testCreateServiceAdvertisement() {
        val message = EnhancedGossipMessageFactory.createServiceAdvertisement(
            serviceId = "ml-service-1",
            serviceName = "Machine Learning Inference",
            serviceType = ServiceType.ML_INFERENCE,
            hostNodeId = "compute-node-1",
            endpoints = mapOf(
                "grpc" to ServiceEndpoint(
                    protocol = "gRPC",
                    address = "192.168.1.100",
                    port = 9090,
                    path = "/ml/inference",
                    authRequired = false,
                    tlsSupported = true
                ),
                "http" to ServiceEndpoint(
                    protocol = "HTTP",
                    address = "192.168.1.100",
                    port = 8080,
                    path = "/api/ml",
                    authRequired = false,
                    tlsSupported = true
                )
            ),
            capabilities = ServiceCapabilities(maxRequests = 1000),
            currentLoad = 0.3f,
            maxCapacity = 1000
        )

        assertEquals("compute-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.SERVICE_ADVERTISEMENT, message.messageType)
        assertEquals(MessagePriority.NORMAL, message.priority)
        
        val payload = message.payload as ServicePayload
        assertEquals("ml-service-1", payload.serviceId)
        assertEquals("Machine Learning Inference", payload.serviceName)
        assertEquals(ServiceType.ML_INFERENCE, payload.serviceType)
        assertEquals("compute-node-1", payload.hostNodeId)
        assertEquals(2, payload.endpoints.size)
        assertEquals(0.3f, payload.currentLoad, 0.01f)
        assertEquals(1000, payload.maxCapacity)
    }

    @Test
    fun testCreateComputeTaskRequest() {
        val message = EnhancedGossipMessageFactory.createComputeTaskRequest(
            taskId = "task-123",
            taskType = ComputeTaskType.ML_INFERENCE,
            requesterNodeId = "client-node-1",
            requirements = ComputeRequirements(minCPU = 0.5f),
            deadline = System.currentTimeMillis() + 300000, // 5 minutes
            priority = TaskPriority.HIGH,
            dependencies = listOf("task-122"),
            targetRuntime = RuntimeEnvironment.TENSORFLOW
        )

        assertEquals("client-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.COMPUTE_TASK_REQUEST, message.messageType)
        assertEquals(MessagePriority.HIGH, message.priority)
        
        val payload = message.payload as ComputeTaskPayload
        assertEquals("task-123", payload.taskId)
        assertEquals(ComputeTaskType.ML_INFERENCE, payload.taskType)
        assertEquals("client-node-1", payload.requesterNodeId)
        assertEquals(TaskPriority.HIGH, payload.priority)
        assertEquals(1, payload.dependencies.size)
        assertEquals("task-122", payload.dependencies[0])
        assertEquals(RuntimeEnvironment.TENSORFLOW, payload.targetRuntimeEnvironment)
    }

    @Test
    fun testCreateI2PRouterAdvertisement() {
        val message = EnhancedGossipMessageFactory.createI2PRouterAdvertisement(
            nodeId = "i2p-node-1",
            i2pCapabilities = I2PCapabilities(
                hasI2PAndroidInstalled = true,
                canRunRouter = true,
                currentRole = I2PRole.I2P_ROUTER,
                routerStatus = I2PRouterStatus.RUNNING,
                tunnelCapacity = 15,
                activeTunnels = 8,
                netDbSize = 75000,
                bandwidthLimits = BandwidthLimits(
                    upload = 2 * 1024 * 1024L, // 2MB/s
                    download = 2 * 1024 * 1024L // 2MB/s
                ),
                participation = I2PParticipation.FULL
            ),
            routerInfo = I2PRouterInfo("0.9.50")
        )

        assertEquals("i2p-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.I2P_ROUTER_ADVERTISEMENT, message.messageType)
        assertEquals(MessagePriority.NORMAL, message.priority)
        
        val payload = message.payload as I2PPayload
        assertEquals(I2PAction.ROUTER_ADVERTISEMENT, payload.action)
        assertEquals("i2p-node-1", payload.nodeId)
        assertNotNull(payload.i2pRouterInfo)
        assertEquals("0.9.50", payload.i2pRouterInfo!!.version)
        assertEquals(I2PRouterStatus.RUNNING, payload.routerStatus)
    }

    @Test
    fun testCreateStorageAdvertisement() {
        val message = EnhancedGossipMessageFactory.createStorageAdvertisement(
            nodeId = "storage-node-1",
            storageCapabilities = StorageCapabilities(
                totalOffered = 500 * 1024 * 1024 * 1024L, // 500GB
                currentlyUsed = 200 * 1024 * 1024 * 1024L, // 200GB
                replicationFactor = 3,
                compressionSupported = true,
                encryptionSupported = true,
                accessPatterns = setOf(AccessPattern.RANDOM, AccessPattern.SEQUENTIAL)
            )
        )

        assertEquals("storage-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.STORAGE_ADVERTISEMENT, message.messageType)
        
        val payload = message.payload as StoragePayload
        assertEquals(StorageAction.OFFER_STORAGE, payload.action)
        assertEquals("storage-node-1", payload.nodeId)
        assertEquals(500 * 1024 * 1024 * 1024L, payload.storageOffered)
        assertEquals(200 * 1024 * 1024 * 1024L, payload.storageUsed)
    }

    @Test
    fun testCreateQuorumProposal() {
        val message = EnhancedGossipMessageFactory.createQuorumProposal(
            quorumId = "quorum-1",
            proposingNodeId = "coordinator-1",
            memberNodes = setOf("node-1", "node-2", "node-3", "coordinator-1"),
            quorumType = QuorumType.STORAGE_QUORUM,
            roleAssignments = mapOf(
                "coordinator-1" to MeshRole.COORDINATOR,
                "node-1" to MeshRole.STORAGE_NODE,
                "node-2" to MeshRole.STORAGE_NODE,
                "node-3" to MeshRole.STORAGE_NODE
            ),
            decisionDeadline = System.currentTimeMillis() + 60000 // 1 minute
        )

        assertEquals("coordinator-1", message.sourceNodeId)
        assertEquals(GossipMessageType.QUORUM_PROPOSAL, message.messageType)
        assertEquals(MessagePriority.HIGH, message.priority)
        
        val payload = message.payload as QuorumPayload
        assertEquals("quorum-1", payload.quorumId)
        assertEquals(QuorumAction.PROPOSE_FORMATION, payload.action)
        assertEquals("coordinator-1", payload.proposingNodeId)
        assertEquals(4, payload.memberNodes.size)
        assertEquals(QuorumType.STORAGE_QUORUM, payload.quorumType)
        assertEquals(4, payload.roleAssignments.size)
        assertEquals(MeshRole.COORDINATOR, payload.roleAssignments["coordinator-1"])
        assertEquals(1, payload.votingRound)
    }

    @Test
    fun testCreateHeartbeat() {
        val timestamp = System.currentTimeMillis()
        val message = EnhancedGossipMessageFactory.createHeartbeat(
            nodeId = "heartbeat-node-1",
            timestamp = timestamp
        )

        assertEquals("heartbeat-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.HEARTBEAT, message.messageType)
        assertEquals(MessagePriority.BACKGROUND, message.priority)
        assertEquals(timestamp, message.timestamp)
        
        val payload = message.payload as NodeStatePayload
        assertEquals("heartbeat-node-1", payload.nodeId)
        assertEquals(0.5f, payload.fitnessScore, 0.01f)
        assertEquals(0.0f, payload.centralityScore, 0.01f)
    }

    @Test
    fun testCreateEmergencyBroadcast() {
        val message = EnhancedGossipMessageFactory.createEmergencyBroadcast(
            sourceNodeId = "emergency-node-1",
            message = "Network partition detected"
        )

        assertEquals("emergency-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.EMERGENCY_BROADCAST, message.messageType)
        assertEquals(MessagePriority.EMERGENCY, message.priority)
        assertEquals(10, message.ttl) // Higher TTL for emergency messages
        
        val payload = message.payload as NodeStatePayload
        assertEquals("emergency-node-1", payload.nodeId)
        assertEquals(0.0f, payload.fitnessScore, 0.01f)
        assertEquals(ThermalState.CRITICAL, payload.thermalState)
        assertEquals(PowerState.BATTERY_CRITICAL, payload.resourceCapabilities.powerState)
    }

    @Test
    fun testCreateNetworkMetrics() {
        val metrics = mapOf(
            "fitness" to 0.78f,
            "centrality" to 0.65f,
            "connectionQuality" to 0.92f,
            "latency" to 45.0f,
            "cpu" to 0.45f,
            "ram" to 0.67f,
            "bandwidth" to 0.83f,
            "storage" to 0.34f,
            "battery" to 0.72f,
            "temperature" to 32.0f,
            "thermalThrottling" to 0.1f
        )
        
        val message = EnhancedGossipMessageFactory.createNetworkMetrics(
            nodeId = "metrics-node-1",
            metrics = metrics
        )

        assertEquals("metrics-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.NETWORK_METRICS, message.messageType)
        assertEquals(MessagePriority.LOW, message.priority)
        
        val payload = message.payload as NodeStatePayload
        assertEquals("metrics-node-1", payload.nodeId)
        assertEquals(0.78f, payload.fitnessScore, 0.01f)
        assertEquals(0.65f, payload.centralityScore, 0.01f)
        assertEquals(0.92f, payload.connectionQuality, 0.01f)
        assertEquals(45L, payload.networkLatency.avgLatency)
        assertEquals(0.45f, payload.resourceCapabilities.availableCPU, 0.01f)
        assertEquals(0.67f, payload.resourceCapabilities.availableRAM.toFloat() / 100f, 0.01f)
        assertEquals(0.83f, payload.resourceCapabilities.availableBandwidth.toFloat() / 100f, 0.01f)
        assertEquals(0.34f, payload.resourceCapabilities.storageOffered.toFloat() / 100f, 0.01f)
        assertEquals(72, payload.batteryInfo.level)
        assertEquals(32, payload.batteryInfo.temperatureCelsius)
        assertFalse(payload.resourceCapabilities.thermalThrottling)
    }

    @Test
    fun testCreateSimpleNodeAnnouncement() {
        val message = createSimpleNodeAnnouncement(
            nodeId = "simple-node-1",
            fitnessScore = 0.67f,
            centralityScore = 0.43f
        )

        assertEquals("simple-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.NODE_ANNOUNCEMENT, message.messageType)
        assertEquals(MessagePriority.NORMAL, message.priority)
        
        val payload = message.payload as NodeStatePayload
        assertEquals("simple-node-1", payload.nodeId)
        assertEquals(NodeType.SMARTPHONE, payload.nodeType)
        assertEquals(0.67f, payload.fitnessScore, 0.01f)
        assertEquals(0.43f, payload.centralityScore, 0.01f)
        assertEquals(1, payload.meshRoles.size)
        assertTrue(payload.meshRoles.contains(MeshRole.MESH_PARTICIPANT))
        assertEquals(100, payload.batteryInfo.level)
        assertEquals(ThermalState.COOL, payload.thermalState)
    }

    @Test
    fun testMessageEquality() {
        val message1 = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
            fitnessScore = 0.5f,
            centralityScore = 0.0f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = 0L,
                availableBandwidth = 0L,
                storageOffered = 0L,
                batteryLevel = 100,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL
        )

        val message2 = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
            fitnessScore = 0.5f,
            centralityScore = 0.0f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = 0L,
                availableBandwidth = 0L,
                storageOffered = 0L,
                batteryLevel = 100,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL
        )

        // Messages should not be equal due to different messageId and timestamp
        assertNotEquals(message1, message2)
        assertNotEquals(message1.hashCode(), message2.hashCode())
    }
}
