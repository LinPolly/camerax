package dev.yanshouwang.camerax

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.view.Surface
import androidx.annotation.IntDef
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry

@androidx.camera.core.ExperimentalGetImage
class NativeCamera(private val activity: Activity, private val textureRegistry: TextureRegistry) {
    private var analyzeMode = AnalyzeMode.NONE

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var camera: Camera
    private lateinit var textureEntry: TextureRegistry.SurfaceTextureEntry

    fun start(call: MethodCall, result: MethodChannel.Result, messenger: (Any) -> Unit?) {
        val future = ProcessCameraProvider.getInstance(activity)
        val executor = ContextCompat.getMainExecutor(activity)
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
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class AnalyzeMode {
    companion object {
        const val NONE = 0
        const val BARCODE = 1
    }
}