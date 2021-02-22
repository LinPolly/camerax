package dev.yanshouwang.camerax

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

/** CameraXPlugin */
class CameraXPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener {
    companion object {
        private const val REQUEST_CODE = 19930430
    }

    private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    private lateinit var activityPluginBinding: ActivityPluginBinding
    private lateinit var method: MethodChannel
    private lateinit var event: EventChannel

    private val cameras: MutableMap<Int, NativeCamera> = mutableMapOf()
    private var sink: EventChannel.EventSink? = null
    private var listener: PluginRegistry.RequestPermissionsResultListener? = null

    private val textureRegistry get() = flutterPluginBinding.textureRegistry
    private val activity get() = activityPluginBinding.activity

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = binding
        method = MethodChannel(flutterPluginBinding.binaryMessenger, "yanshouwang.dev/camerax/method")
        event = EventChannel(flutterPluginBinding.binaryMessenger, "yanshouwang.dev/camerax/event")
        method.setMethodCallHandler(this)
        event.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        method.setMethodCallHandler(null)
        event.setStreamHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityPluginBinding = binding
        activityPluginBinding.addRequestPermissionsResultListener(this)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityPluginBinding.removeRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val hashCode = call.argument<Int>("hashCode")!!
        when (call.method) {
            "check" -> checkNative(result)
            "request" -> requestNative(result)
            "create" -> cameras[hashCode] = NativeCamera(activity, textureRegistry)
            "start" -> cameras[hashCode]!!.start(call, result) { event: Any -> sink?.success(event) }
            "stop" -> cameras[hashCode]!!.stop(result)
            "torch" -> cameras[hashCode]!!.torch(call, result)
            "analyze" -> cameras[hashCode]!!.analyze(call, result)
            "dispose" -> cameras.remove(hashCode)
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?): Boolean {
        return listener?.onRequestPermissionsResult(requestCode, permissions, grantResults) ?: false
    }

    private fun checkNative(result: MethodChannel.Result) {
        // Can't get exact denied or not_determined state without request. Just return not_determined when state isn't authorized
        val value =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        result.success(value)
    }

    private fun requestNative(result: MethodChannel.Result) {
        listener = PluginRegistry.RequestPermissionsResultListener { requestCode, _, grantResults ->
            if (requestCode != REQUEST_CODE) {
                false
            } else {
                val authorized = grantResults[0] == PackageManager.PERMISSION_GRANTED
                result.success(authorized)
                listener = null
                true
            }
        }
        val permissions = arrayOf(Manifest.permission.CAMERA)
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
    }
}
