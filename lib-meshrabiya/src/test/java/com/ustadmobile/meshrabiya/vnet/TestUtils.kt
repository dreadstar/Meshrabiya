package com.ustadmobile.meshrabiya.vnet

/**
 * Small test helper to defensively close resources that may or may not implement a common
 * close/cleanup API. Used to avoid repeating try/catch blocks across tests.
 */
object TestUtils {
    fun safeClose(vararg objs: Any?) {
        for (o in objs) {
            if (o == null) continue
            try {
                when (o) {
                    is AutoCloseable -> o.close()
                    is java.io.Closeable -> o.close()
                    is VirtualNode -> o.close()
                    else -> {
                        // Best-effort: try to call a no-arg close() method via reflection
                        try {
                            val m = o.javaClass.getMethod("close")
                            m.invoke(o)
                        } catch (_: NoSuchMethodException) {
                            // ignore
                        }
                    }
                }
            } catch (_: Throwable) {
                // swallow any exception during test cleanup
            }
        }
    }
}
