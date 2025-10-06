package com.ustadmobile.meshrabiya.net

/**
 * Provides socket timeout configuration for ServerSocket.accept() and accepted socket SO_TIMEOUT.
 * Default behaviour derives test-mode from system property "meshrabiya.hardware.testMode".
 * Default values: test-mode -> 2000ms, production -> 0 (no timeout).
 */
interface SocketTimeoutsProvider {
    /** Accept timeout in milliseconds; <= 0 means no timeout (blocking). */
    val acceptTimeoutMillis: Int

    /** SO_TIMEOUT for accepted sockets in milliseconds; <= 0 means no timeout. */
    val socketSoTimeoutMillis: Int

    /** Connect timeout in milliseconds for stream sockets; <= 0 means default/no override. */
    val connectTimeoutMillis: Int

    /** Read timeout in milliseconds for stream sockets / HTTP clients; <= 0 means default/no override. */
    val readTimeoutMillis: Int

    /** Write timeout in milliseconds for stream sockets / HTTP clients; <= 0 means default/no override. */
    val writeTimeoutMillis: Int
}

/** Default provider: reads system property `meshrabiya.hardware.testMode`. */
class DefaultSocketTimeoutsProvider : SocketTimeoutsProvider {
    private val SYS_PROP_TEST_MODE = "meshrabiya.hardware.testMode"

    private fun isTestMode(): Boolean = java.lang.Boolean.getBoolean(SYS_PROP_TEST_MODE)

    override val acceptTimeoutMillis: Int
        // Prefer blocking accept by default. Tests that need an accept timeout should
        // explicitly construct a TestSocketTimeoutsProvider with a non-zero value.
        get() = 0

    override val socketSoTimeoutMillis: Int
        get() = if (isTestMode()) 2000 else 0

    // For higher-level clients (OkHttp etc.) map connect/read/write to the same test-mode timeout
    override val connectTimeoutMillis: Int
        get() = if (isTestMode()) 2000 else 0

    override val readTimeoutMillis: Int
        get() = if (isTestMode()) 2000 else 0

    override val writeTimeoutMillis: Int
        get() = if (isTestMode()) 2000 else 0
}

/** Simple test provider where callers can set values explicitly. */
class TestSocketTimeoutsProvider(
    /** Default tests should prefer blocking accept to avoid noisy accept-timeout loops. */
    override val acceptTimeoutMillis: Int = 0,
    override val socketSoTimeoutMillis: Int = 0
) : SocketTimeoutsProvider

{
    override val connectTimeoutMillis: Int = acceptTimeoutMillis
    override val readTimeoutMillis: Int = socketSoTimeoutMillis
    override val writeTimeoutMillis: Int = socketSoTimeoutMillis
}
