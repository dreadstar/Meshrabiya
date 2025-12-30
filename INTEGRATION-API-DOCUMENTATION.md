# Meshrabiya Library Integration API Documentation

This document provides a comprehensive, user-friendly reference for integrating the Meshrabiya library into other projects. It covers all public API functions and Data Transfer Objects (DTOs), with clear explanations of their purpose, signatures, argument types, and usage.

---

## 1. API Interface: `MeshrabiyaApi`

The `MeshrabiyaApi` interface exposes all key operations, state, and event registration for UI/control layers. It is implemented by `MeshrabiyaApiImpl`.

### Core Methods

#### Context and Initialization
- **`provideAppContext(context: Context)`**
  - **Purpose:** Supplies the application context to the library for internal use.
  - **Arguments:**
    - `context`: Android `Context` object.

- **`getAppContext(): Context?`**
  - **Purpose:** Retrieves the application context previously provided.
  - **Returns:** Android `Context` or null if not set.

- **`initMesh(context: Context)`**
  - **Purpose:** Initializes the mesh network and internal managers.
  - **Arguments:**
    - `context`: Android `Context` object.

#### Mesh State & Network Info
- **`getNodeRole(): Byte`**
  - **Purpose:** Returns the current mesh node role as a byte value.

- **`getFitnessScore(): Float`**
  - **Purpose:** Returns the node's fitness score (float).

- **`getConnectionUri(): String`**
  - **Purpose:** Returns the connection URI for the node.

- **`getLocalNodeState(): LocalNodeState`**
  - **Purpose:** Returns the local node's state object.

- **`getNeighbors(): List<Int>`**
  - **Purpose:** Returns a list of neighbor node IDs.

- **`getHopCountToNode(nodeId: Int): Int?`**
  - **Purpose:** Returns the hop count to a given node ID, or null if unreachable.
  - **Arguments:**
    - `nodeId`: Integer node ID.

- **`getConnectLink(): String?`**
  - **Purpose:** Returns the current connect link as a string, or null if unavailable.

- **`getConnectLinkFlow(): Flow<String?>`**
  - **Purpose:** Returns a Kotlin Flow emitting connect link updates.

#### Mesh Network Controls
- **`startMesh(callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Starts the mesh network. Calls the callback with success or error.
  - **Arguments:**
    - `callback`: Function taking a `Result<Unit>`.

- **`stopMesh(callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Stops the mesh network. Calls the callback with success or error.

- **`getMeshStatus(): MeshState`**
  - **Purpose:** Returns the current mesh state (enum).

- **`getPeerCount(): Int`**
  - **Purpose:** Returns the number of connected peers.

- **`getNetworkInfo(): NetworkInfo`**
  - **Purpose:** Returns detailed network information.

- **`getNodeInfo(nodeId: String): NodeInfo`**
  - **Purpose:** Returns information about a specific node.
  - **Arguments:**
    - `nodeId`: String node identifier.

- **`getNodeId(): Int`**
  - **Purpose:** Returns the local node's integer ID.

#### Proxy and Gateway Controls
- **`setProxy(host: String, port: Int)`**
  - **Purpose:** Sets a proxy for mesh traffic.
  - **Arguments:**
    - `host`: Proxy host (String)
    - `port`: Proxy port (Int)

- **`setProxyActive(active: Boolean)`**
  - **Purpose:** Enables or disables the proxy.
  - **Arguments:**
    - `active`: Boolean flag

- **`setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Enables/disables Tor gateway role. Calls callback with result.

- **`getTorGatewayStatus(): Boolean`**
  - **Purpose:** Returns true if Tor gateway is enabled.

- **`setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Enables/disables internet gateway role. Calls callback with result.

- **`getInternetGatewayStatus(): Boolean`**
  - **Purpose:** Returns true if internet gateway is enabled.

- **`getGatewayStatus(): Boolean`**
  - **Purpose:** Returns true if any gateway role is enabled.

- **`setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Sets the preferred gateway routing policy.
  - **Arguments:**
    - `preference`: Enum value (TOR_ONLY, CLEARNET_ONLY, EITHER)

- **`getGatewayPreference(): GatewayPreference`**
  - **Purpose:** Gets the current gateway preference.

- **`isTorActive(): Boolean`**
  - **Purpose:** Returns true if Tor is currently active.

#### Storage Participation
- **`setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Enables/disables distributed storage participation.

- **`getStorageParticipationStatus(): Boolean`**
  - **Purpose:** Returns true if storage participation is enabled.

- **`getAvailableStorageDevices(): List<StorageDeviceDto>`**
  - **Purpose:** Returns a list of available storage devices (DTOs).

- **`setStorageAllocation(deviceId: String, path: String, allocatedMB: Long)`**
  - **Purpose:** Sets the storage allocation for a device/path.
  - **Arguments:**
    - `deviceId`: String device ID
    - `path`: String path
    - `allocatedMB`: Long, megabytes allocated

- **`getStorageAllocations(): List<StorageAllocation>`**
  - **Purpose:** Returns a list of current storage allocations.

- **`enableDistributedStorage()`**
  - **Purpose:** Enables distributed storage features.

- **`disableDistributedStorage()`**
  - **Purpose:** Disables distributed storage features.

- **`isComputeLayerParticipating(): Boolean`**
  - **Purpose:** Returns true if the compute layer is participating.

- **`setDropFolderPath(path: String)`**
  - **Purpose:** Sets the path for the drop folder.
  - **Arguments:**
    - `path`: String path

- **`getDropFolderPath(): String`**
  - **Purpose:** Gets the current drop folder path.

- **`setComputeLayerParticipatingEnabled(enabled: Boolean)`**
  - **Purpose:** Enables/disables compute layer participation.

#### Drop Folder Management
- **`selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Selects a drop folder for file sharing.

- **`getDropFolder(): File?`**
  - **Purpose:** Returns the current drop folder as a File, or null.

- **`getDropFolderFiles(): List<File>`**
  - **Purpose:** Returns a list of files in the drop folder.

- **`setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit)`**
  - **Purpose:** Registers a callback for drop folder updates.

#### File Operations
- **`storeFile(file: File, recipients: List<RecipientEntryDto>)`**
  - **Purpose:** Stores a file in the mesh network for the given recipients.
  - **Arguments:**
    - `file`: File to store
    - `recipients`: List of recipient DTOs

- **`suspend retrieveFile(fileId: String): ByteArray?`**
  - **Purpose:** Retrieves a file by its ID. Returns file bytes or null.
  - **Arguments:**
    - `fileId`: String file identifier

- **`streamFile(fileId: String, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Streams a file by ID. Calls callback with result.

- **`deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Deletes a file by ID. Calls callback with result.

- **`getAllMeshFiles(): List<MeshFile>`**
  - **Purpose:** Returns all mesh files known to the node.

#### Distributed Service Layer
- **`setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Enables/disables participation in a distributed service.

- **`getAvailableServices(): List<String>`**
  - **Purpose:** Returns a list of available service IDs.

- **`getServiceParticipationStatus(serviceId: String): Boolean`**
  - **Purpose:** Returns true if participating in the given service.

#### Compute/Task Operations
- **`addTask(requestParams: Map<String, Any>): ApiResult`**
  - **Purpose:** Adds a compute task to the mesh network.
  - **Arguments:**
    - `requestParams`: Map of task parameters

- **`startTask(taskId: String, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Starts a compute task by ID.

- **`cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)`**
  - **Purpose:** Cancels a compute task by ID.

#### Event Registration
- **`setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)`**
  - **Purpose:** Registers a callback for file retrieval events.

- **`setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit)`**
  - **Purpose:** Registers a callback for file storage events.

- **`setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)`**
  - **Purpose:** Registers a callback for permission update events.

- **`setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)`**
  - **Purpose:** Registers a callback for operation failures.

- **`setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)`**
  - **Purpose:** Registers a callback for file sharing events.

- **`setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)`**
  - **Purpose:** Registers a callback for files added to the drop folder.

- **`setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean)`**
  - **Purpose:** Registers a callback for gateway traffic events.

- **`getMeshTrafficRouterStatus(): String`**
  - **Purpose:** Returns the status of the mesh traffic router.

- **`setOnMeshStateChanged(handler: (newState: MeshState) -> Unit)`**
  - **Purpose:** Registers a callback for mesh state changes.

- **`setOnPeerCountChanged(handler: (newCount: Int) -> Unit)`**
  - **Purpose:** Registers a callback for peer count changes.

- **`setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)`**
  - **Purpose:** Registers a callback for gossip messages.

- **`setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit)`**
  - **Purpose:** Registers a callback for task status updates.

#### User Identity API
- **`getUserInfo(): User`**
  - **Purpose:** Returns the current user info (userId, publicKey, nickname, etc.).

- **`setUserNickname(nickname: String)`**
  - **Purpose:** Sets the user's nickname.
  - **Arguments:**
    - `nickname`: String

- **`rotateUserKey(): User`**
  - **Purpose:** Rotates the user's keypair and updates userId/publicKey.

---

## 2. Data Transfer Objects (DTOs)

### DropFolderItemDto
- **Purpose:** Represents an item in the drop folder (file or folder).
- **Properties:**
  - `itemRelativePath: String` — Relative path of the item.
  - `isFolder: Boolean` — True if the item is a folder.
  - `trigger: StoreFileTriggerDto?` — Optional trigger for file storage.

### RecipientEntryDto
- **Purpose:** Represents a recipient for file sharing.
- **Properties:**
  - `publicKey: String` — Base64-encoded public key of the recipient.
  - `recipientType: RecipientTypeDto` — Type of recipient (USER or TASK).
  - `recipientId: String` — Unique recipient identifier.
  - `expiresAt: Long?` — Optional expiration timestamp (milliseconds since epoch).

### RecipientTypeDto (enum)
- **Purpose:** Enumerates recipient types.
- **Values:**
  - `USER` — Long-lived user/node keypair.
  - `TASK` — Ephemeral task keypair.

### StoreFileTriggerDto
- **Purpose:** Represents a trigger for storing a file.
- **Properties:**
  - `id: Int` — Trigger identifier.
  - `subPath: String` — Sub-path for the trigger.
  - `recipients: List<RecipientEntryDto>` — List of recipients for the trigger.

### StorageAllocationDto
- **Purpose:** Represents a storage allocation for a device/path.
- **Properties:**
  - `deviceId: String` — Device identifier.
  - `path: String` — Path for the allocation.
  - `allocatedMB: Long` — Allocated storage in megabytes.

### StorageDeviceTypeDto (enum)
- **Purpose:** Enumerates storage device types.
- **Values:**
  - `INTERNAL` — Internal device storage.
  - `EXTERNAL` — External (SD card, etc.).
  - `USB` — USB-attached storage.

### StorageDeviceDto
- **Purpose:** Represents a storage device available for participation.
- **Properties:**
  - `id: String` — Device identifier.
  - `name: String` — Human-readable device name.
  - `path: String` — Device path.
  - `availableSpaceGB: Float` — Available space in gigabytes.
  - `totalSpaceGB: Float` — Total space in gigabytes.
  - `type: StorageDeviceTypeDto` — Device type (enum).
- **Extension Function:**
  - `getFormattedAvailableSpace(): String` — Returns a human-readable string for available space (e.g., "10 GB").

---

## 3. Conversion Functions

The library provides extension functions to convert between internal models and DTOs for all major types. These are used for API boundaries and serialization.

- `DropFolderItem.toDto(): DropFolderItemDto`
- `DropFolderItemDto.toInternal(): DropFolderItem`
- `StoreFileTrigger.toDto(): StoreFileTriggerDto`
- `StoreFileTriggerDto.toInternal(): StoreFileTrigger`
- `RecipientEntry.toDto(): RecipientEntryDto`
- `RecipientEntryDto.toInternal(): RecipientEntry`
- `StorageAllocation.toDto(): StorageAllocationDto`
- `StorageAllocationDto.toInternal(): StorageAllocation`
- `StorageDeviceTypeDto.toInternal(): StorageDeviceType`
- `StorageDeviceDto.toInternal(): StorageDevice`
- `StorageDeviceType.toDto(): StorageDeviceTypeDto`
- `StorageDevice.toDto(): StorageDeviceDto`

---

## 4. Usage Notes

- Always initialize the library with `provideAppContext` and `initMesh` before using other features.
- Use DTOs for all API boundaries and serialization.
- Register event handlers as needed for file, folder, and network events.
- Use the conversion functions to map between internal models and DTOs.

---

For further details, see the source files `MeshrabiyaApi.kt` and `MeshrabiyaApiImpl.kt` in the Meshrabiya library.
