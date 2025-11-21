package com.ustadmobile.meshrabiya.service.compute

// Container execution, monitoring, and resource usage extraction
import kotlinx.coroutines.*
import com.ustadmobile.meshrabiya.service.compute.ResourceMonitoring
import com.ustadmobile.meshrabiya.service.compute.MicroContainer

object ContainerExecution {
    fun executeInContainer(container: MicroContainer, code: ByteArray): ExecutionResult {
        // Launch isolated process, run code, monitor resource usage
        val processId = container.launchIsolatedProcess(code)
        val metrics = ResourceMonitoring.getContainerMetrics(container.containerId)
        return ExecutionResult(processId, metrics)
    }
    fun monitorContainerExecution(container: MicroContainer): ResourceMonitoring.ResourceMetrics {
        return ResourceMonitoring.getContainerMetrics(container.containerId)
    }
}

data class ExecutionResult(val processId: Int, val metrics: ResourceMonitoring.ResourceMetrics)
