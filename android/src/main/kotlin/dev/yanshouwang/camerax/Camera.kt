package dev.yanshouwang.camerax

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.IntDef
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class Camera(@LensFacing val facing: Int, private val activity: Activity, private val textureRegistry: TextureRegistry) {
    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String
    private val previewSize: Size

    private var analyzeMode = AnalyzeMode.NONE

    private lateinit var camera: CameraDevice
    private lateinit var textureEntry: TextureRegistry.SurfaceTextureEntry
    private lateinit var session: CameraCaptureSession

    private lateinit var cameraProvider: ProcessCameraProvider

    private val lifecycleScope get() = (activity as LifecycleOwner).lifecycleScope

    init {
        cameraManager.cameraIdList
                .filter { cameraId ->
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    characteristics[CameraCharacteristics.LENS_FACING] == facing
                }
                .forEach { cameraId -> Log.d(TAG, "camera id for $facing: $cameraId") }
        cameraId = cameraManager.cameraIdList
                .first { cameraId ->
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    characteristics[CameraCharacteristics.LENS_FACING] == facing
                }
        previewSize = computeBestPerviewSize()
    }

    fun start() = lifecycleScope.launch(Dispatchers.Main) {
        camera = openCamera()
        textureEntry = textureRegistry.createSurfaceTexture()
        val texture = textureEntry.surfaceTexture()
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        val surface = Surface(texture)
        val targets = listOf(surface)
        session = createCaptureSession(targets)
    }

    private suspend fun openCamera(handler: Handler? = null) = suspendCoroutine<CameraDevice> { continuation ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) = continuation.resume(camera)

            override fun onClosed(camera: CameraDevice) {
                super.onClosed(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                TODO("Not yet implemented")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val message = when (error) {
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    else -> "Unknown"
                }
                val exception = RuntimeException("Camera $cameraId error: ($error) $message")
                continuation.resumeWithException(exception)
            }

        }, handler)
    }

    private suspend fun createCaptureSession(targets: List<Surface>, handler: Handler? = null) = suspendCoroutine<CameraCaptureSession> { continuation ->
        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = continuation.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exception = RuntimeException("Camera $cameraId session configuration failed")
                Log.e(TAG, exception.message, exception)
                continuation.resumeWithException(exception)
            }

        }, handler)
    }

    fun start(call: MethodCall, result: MethodChannel.Result, messenger: (Any) -> Unit?) {
        val executor = ContextCompat.getMainExecutor(activity)

        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(Runnable {
            cameraProvider = future.get()
            textureEntry = textureRegistry.createSurfaceTexture()
            val textureId = textureEntry.id()
            // Preview
            val surfaceProvider = Preview.SurfaceProvider { request ->
                val resolution = request.resolution
                val texture = textureEntry.surfaceTexture()
                texture.setDefaultBufferSize(resolution.width, resolution.height)
                val surface = Surface(texture)
                request.provideSurface(surface, executor, Consumer { })
            }
            val preview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
            // Analyzer
            val analyzer = ImageAnalysis.Analyzer { image -> // YUV_420_888 format
                when (analyzeMode) {
                    AnalyzeMode.BARCODE -> {
                        val mediaImage = image.image ?: return@Analyzer
                        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                        val scanner = BarcodeScanning.getClient()
                        scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        val event = mapOf("key" to "barcode", "value" to barcode.data)
                                        messenger(event)
                                    }
                                }
                                .addOnFailureListener { e -> Log.e(TAG, e.message, e) }
                                .addOnCompleteListener { image.close() }
                    }
                    else -> image.close()
                }
            }
            val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply { setAnalyzer(executor, analyzer) }
            // Bind to lifecycle.
            val owner = activity as LifecycleOwner
            val selector =
                    if (call.arguments == 0) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
            camera = cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
            camera.cameraInfo.torchState.observe(owner, Observer { state ->
                // TorchState.OFF = 0; TorchState.ON = 1
                val event = mapOf("name" to "torchState", "data" to state)
                messenger(event)
            })
            // TODO: seems there's not a better way to get the final resolution
            @SuppressLint("RestrictedApi")
            val resolution = preview.attachedSurfaceResolution!!
            val portrait = camera.cameraInfo.sensorRotationDegrees % 180 == 0
            val width = resolution.width.toDouble()
            val height = resolution.height.toDouble()
            val size = if (portrait) mapOf("width" to width, "height" to height) else mapOf("width" to height, "height" to width)
            val answer = mapOf("textureId" to textureId, "size" to size, "torchable" to camera.torchable)
            result.success(answer)
        }, executor)
    }

    fun torch(call: MethodCall, result: MethodChannel.Result) {
        val state = call.arguments == 1
        camera.cameraControl.enableTorch(state)
        result.success(null)
    }

    fun analyze(call: MethodCall, result: MethodChannel.Result) {
        analyzeMode = call.arguments as Int
        result.success(null)
    }

    fun stop(result: MethodChannel.Result) {
        val owner = activity as LifecycleOwner
        camera.cameraInfo.torchState.removeObservers(owner)
        cameraProvider.unbindAll()
        textureEntry.release()

        result.success(null)
    }
}

@IntDef(AnalyzeMode.NONE, AnalyzeMode.BARCODE)
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD)
annotation class AnalyzeMode {
    companion object {
        const val NONE = 0
        const val BARCODE = 1
    }
}

@IntDef(LensFacing.FRONT, LensFacing.BACK, LensFacing.EXTERNAL)
@Retention(AnnotationRetention.SOURCE)
annotation class LensFacing {
    companion object {
        const val FRONT = 0
        const val BACK = 1
        const val EXTERNAL = 2
    }
}
