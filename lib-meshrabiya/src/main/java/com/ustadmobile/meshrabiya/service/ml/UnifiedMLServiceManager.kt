package com.ustadmobile.meshrabiya.service.ml

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified ML service manager supporting all three ML tiers:
 * 1. ML Kit Native (Google-optimized, 0 additional storage)
 * 2. ML Kit Custom (Firebase-managed models)
 * 3. LiteRT Direct (Full TensorFlow Lite flexibility)
 */
class UnifiedMLServiceManager(
    private val context: Context,
    private val deviceCapabilities: DeviceCapabilities
) {
    
    companion object {
        private const val TAG = "UnifiedMLServiceManager"
    }
    
    // Tier 1: ML Kit Native Services (always available)
    private val mlKitNativeServices = ConcurrentHashMap<String, MLKitNativeWrapper>()
    
    // Tier 2: ML Kit Custom Models (Firebase or bundled)
    private val mlKitCustomServices = ConcurrentHashMap<String, MLKitCustomWrapper>()
    
    // Tier 3: Direct LiteRT Services (TensorFlow Lite models)
    private val literTServices = ConcurrentHashMap<String, LiteRTWrapper>()
    
    private val isInitialized = CompletableDeferred<Boolean>()
    
    /**
     * Initialize ML services based on device capabilities
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                initializeMLKitNativeServices()
                initializeMLKitCustomServices()
                initializeLiteRTServices()
                
                isInitialized.complete(true)
                Log.i(TAG, "ML services initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ML services", e)
                isInitialized.complete(false)
            }
        }
    }
    
    /**
     * Process ML request using optimal tier
     */
    suspend fun processMLRequest(
        serviceType: String,
        serviceId: String,
        input: MLServiceInput
    ): MLServiceResult {
        // Ensure initialization is complete
        isInitialized.await()
        
        return when (serviceType) {
            "ml-kit-native" -> processMLKitNative(serviceId, input)
            "ml-kit-custom" -> processMLKitCustom(serviceId, input)
            "litert" -> processLiteRT(serviceId, input)
            else -> MLServiceResult.error("Unknown service type: $serviceType")
        }
    }
    
    /**
     * Get available ML services on this device
     */
    fun getAvailableServices(): List<ServiceAnnouncement> {
        val services = mutableListOf<ServiceAnnouncement>()
        
        // Add ML Kit native services
        mlKitNativeServices.forEach { (serviceId, wrapper) ->
            services.add(wrapper.getServiceAnnouncement())
        }
        
        // Add ML Kit custom services
        mlKitCustomServices.forEach { (serviceId, wrapper) ->
            services.add(wrapper.getServiceAnnouncement())
        }
        
        // Add LiteRT services
        literTServices.forEach { (serviceId, wrapper) ->
            services.add(wrapper.getServiceAnnouncement())
        }
        
        return services
    }
    
    private fun initializeMLKitNativeServices() {
        // Tier 1: Always available ML Kit services
        
        // Text Recognition (always available)
        mlKitNativeServices["text-recognition"] = TextRecognitionWrapper()
        
        // Face Detection (if sufficient memory)
        if (deviceCapabilities.memoryMB > 2000) {
            mlKitNativeServices["face-detection"] = FaceDetectionWrapper()
        }
        
        // Object Detection and Translation (high-end devices)
        if (deviceCapabilities.memoryMB > 4000) {
            mlKitNativeServices["object-detection"] = ObjectDetectionWrapper()
            mlKitNativeServices["translation"] = TranslationWrapper()
        }
        
        Log.i(TAG, "Initialized ${mlKitNativeServices.size} ML Kit native services")
    }
    
    private fun initializeMLKitCustomServices() {
        // Tier 2: Custom models via ML Kit API
        if (!deviceCapabilities.mlKitCustomSupport) return
        
        if (deviceCapabilities.memoryMB > 3000) {
            // Custom image labeling
            mlKitCustomServices["custom-image-classifier"] = 
                CustomImageLabelingWrapper("models/custom_classifier.tflite")
        }
        
        if (deviceCapabilities.memoryMB > 4000) {
            // Custom object detection  
            mlKitCustomServices["custom-object-detector"] =
                CustomObjectDetectionWrapper("models/custom_detector.tflite")
        }
        
        Log.i(TAG, "Initialized ${mlKitCustomServices.size} ML Kit custom services")
    }
    
    private fun initializeLiteRTServices() {
        // Tier 3: Direct TensorFlow Lite models
        if (!deviceCapabilities.hasLiteRT) return
        
        // Lightweight models for all LiteRT-capable devices
        if (deviceCapabilities.memoryMB > 1000) {
            literTServices["sentiment-analyzer"] = 
                LiteRTWrapper("models/sentiment_analysis.tflite", createInterpreterOptions())
        }
        
        // Medium models for capable devices
        if (deviceCapabilities.memoryMB > 3000) {
            literTServices["named-entity-recognizer"] =
                LiteRTWrapper("models/ner_model.tflite", createInterpreterOptions())
        }
        
        // Large models for powerhouse devices
        if (deviceCapabilities.memoryMB > 6000) {
            literTServices["document-summarizer"] =
                LiteRTWrapper("models/summarization_large.tflite", createInterpreterOptions())
        }
        
        Log.i(TAG, "Initialized ${literTServices.size} LiteRT services")
    }
    
    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        
        // Hardware acceleration if available
        if (deviceCapabilities.hasGPUAcceleration) {
            options.addDelegate(GpuDelegate())
        }
        
        if (deviceCapabilities.hasNNAPI) {
            options.addDelegate(NnApiDelegate())
        }
        
        // Set threads based on CPU cores
        options.setNumThreads(minOf(4, deviceCapabilities.cpuCores))
        
        return options
    }
    
    private suspend fun processMLKitNative(serviceId: String, input: MLServiceInput): MLServiceResult {
        val wrapper = mlKitNativeServices[serviceId] 
            ?: return MLServiceResult.error("Service not available: $serviceId")
        
        return withContext(Dispatchers.Default) {
            wrapper.process(input)
        }
    }
    
    private suspend fun processMLKitCustom(serviceId: String, input: MLServiceInput): MLServiceResult {
        val wrapper = mlKitCustomServices[serviceId]
            ?: return MLServiceResult.error("Custom service not available: $serviceId")
        
        return withContext(Dispatchers.Default) {
            wrapper.process(input)
        }
    }
    
    private suspend fun processLiteRT(serviceId: String, input: MLServiceInput): MLServiceResult {
        val wrapper = literTServices[serviceId]
            ?: return MLServiceResult.error("LiteRT service not available: $serviceId")
        
        return withContext(Dispatchers.Default) {
            wrapper.process(input)
        }
    }
}

// Base interface for ML service wrappers
interface MLServiceWrapper {
    suspend fun process(input: MLServiceInput): MLServiceResult
    fun getServiceAnnouncement(): ServiceAnnouncement
}

// Tier 1: ML Kit Native Implementation
class TextRecognitionWrapper : MLServiceWrapper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return try {
            val image = InputImage.fromBitmap(input.bitmap, 0)
            val result = recognizer.process(image).await()
            
            MLServiceResult.success(mapOf(
                "text" to result.text,
                "confidence" to 0.95f // ML Kit doesn't provide confidence, use default
            ))
        } catch (e: Exception) {
            MLServiceResult.error("Text recognition failed: ${e.message}")
        }
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "text-recognition",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 0, // Via Google Play Services
            capabilities = listOf("ocr", "text-extraction", "real-time"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 512,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 150,
                maxExecutionTimeMs = 5000
            )
        )
    }
}

// Placeholder implementations for other wrappers
class FaceDetectionWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Implementation using ML Kit Face Detection API
        return MLServiceResult.success(mapOf("faces" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "face-detection",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 20 * 1024, // 20MB via Play Services
            capabilities = listOf("face-detection", "landmarks", "real-time"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 2000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 500,
                maxExecutionTimeMs = 10000
            )
        )
    }
}

class ObjectDetectionWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf("objects" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "object-detection",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 45 * 1024,
            capabilities = listOf("object-detection", "tracking", "classification"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 800,
                maxExecutionTimeMs = 15000
            )
        )
    }
}

class TranslationWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf("translated_text" to input.text))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "translation",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 30 * 1024,
            capabilities = listOf("translation", "multilingual", "offline"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 1200,
                maxExecutionTimeMs = 20000
            )
        )
    }
}

// Tier 2: ML Kit Custom Model Wrappers
class CustomImageLabelingWrapper(private val modelPath: String) : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Implementation using ML Kit Custom Image Labeling
        return MLServiceResult.success(mapOf("labels" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "custom-image-classifier",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_CUSTOM,
            version = "1.0.0",
            sizeKB = 15 * 1024,
            capabilities = listOf("image-classification", "custom-labels"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 3000,
                minStorageMB = 15 * 1024
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 1500,
                maxExecutionTimeMs = 25000
            )
        )
    }
}

class CustomObjectDetectionWrapper(private val modelPath: String) : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf("detections" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "custom-object-detector", 
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_CUSTOM,
            version = "1.0.0",
            sizeKB = 35 * 1024,
            capabilities = listOf("object-detection", "custom-classes"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 35 * 1024
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 2500,
                maxExecutionTimeMs = 30000
            )
        )
    }
}

// Tier 3: Direct LiteRT Implementation
class LiteRTWrapper(
    private val modelPath: String,
    private val options: Interpreter.Options
) : MLServiceWrapper {
    
    private var interpreter: Interpreter? = null
    
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return try {
            val interp = interpreter ?: loadModel()
            
            // Simplified inference - real implementation would handle tensor shapes
            val result = performInference(interp, input)
            
            MLServiceResult.success(mapOf("result" to result))
        } catch (e: Exception) {
            MLServiceResult.error("LiteRT inference failed: ${e.message}")
        }
    }
    
    private fun loadModel(): Interpreter {
        // Load model from assets or file system
        val modelFile = context.assets.open(modelPath)
        val modelBytes = modelFile.readBytes()
        
        val interpreter = Interpreter(modelBytes, options)
        this.interpreter = interpreter
        return interpreter
    }
    
    private fun performInference(interpreter: Interpreter, input: MLServiceInput): Any {
        // Placeholder - real implementation would handle proper tensor operations
        return "inference_result"
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = modelPath.substringAfterLast("/").substringBeforeLast("."),
            serviceType = ServiceAnnouncement.ServiceType.LITERT,
            version = "1.0.0",
            sizeKB = 25 * 1024, // Estimated model size
            capabilities = listOf("custom-inference", "tensorflow-lite"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 1000,
                minStorageMB = 25 * 1024
            ),
            executionProfile = ExecutionProfile(
                averageExecutionTimeMs = 2000,
                maxExecutionTimeMs = 30000
            )
        )
    }
}

// Data classes for ML service I/O
data class MLServiceInput(
    val bitmap: android.graphics.Bitmap? = null,
    val text: String? = null,
    val audioData: ByteArray? = null,
    val parameters: Map<String, Any> = emptyMap()
)

data class MLServiceResult(
    val success: Boolean,
    val data: Map<String, Any>? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0,
    val confidence: Float = 0f
) {
    companion object {
        fun success(data: Map<String, Any>): MLServiceResult {
            return MLServiceResult(success = true, data = data)
        }
        
        fun error(message: String): MLServiceResult {
            return MLServiceResult(success = false, error = message)
        }
    }
}

// Extension function for Task.await() - would be in a separate utils file
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.tasks.await(this)
}
