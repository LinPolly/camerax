import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'barcode.dart';
import 'camera_view.dart';
import 'camera_view_args.dart';
import 'enum.dart';
import 'messenger.dart';
import 'util.dart';

/// A camera controller.
abstract class Camera {
  factory Camera(CameraFacing facing) => _Camera(facing);

  /// Arguments for [CameraView].
  Widget get view;

  /// Torch state of the camera.
  ValueNotifier<TorchState> get torchState;

  /// A stream of barcodes.
  Stream<Barcode> get barcodes;

  /// Start the camera.
  void start();

  /// Stop the camera.
  void stop();

  /// Switch the torch's state.
  void torch();

  /// Release the resources of the camera.
  void dispose();
}

class _Camera implements Camera {
  static const undetermined = 0;
  static const authorized = 1;
  static const denied = 2;

  static const analyze_none = 0;
  static const analyze_barcode = 1;

  final CameraFacing facing;
  final ValueNotifier<CameraViewArgs> viewArgs;

  StreamSubscription subscription;

  @override
  final ValueNotifier<TorchState> torchState;

  bool torchable;
  StreamController<Barcode> barcodesController;

  @override
  Widget get view => CameraView(viewArgs);
  @override
  Stream<Barcode> get barcodes => barcodesController.stream;

  _Camera(this.facing)
      : viewArgs = ValueNotifier(null),
        torchState = ValueNotifier(TorchState.off),
        torchable = false {
    // Listen event handler.
    subscription = stream.listen(handleEvent);
    // Create barcode stream controller.
    barcodesController = StreamController.broadcast(
      onListen: () => method.invokeMethod('analyze', analyze_barcode),
      onCancel: () => method.invokeMethod('analyze', analyze_none),
    );
  }

  void handleEvent(dynamic event) {
    final key = event['key'];
    final value = event['value'];
    switch (key) {
      case 'torchState':
        final state = TorchState.values[value];
        torchState.value = state;
        break;
      case 'barcode':
        final barcode = Barcode.fromNative(value);
        barcodesController.add(barcode);
        break;
      default:
        throw UnimplementedError();
    }
  }

  @override
  void start() async {
    // Check authorization state.
    var state = await method.invokeMethod('state');
    if (state == undetermined) {
      final result = await method.invokeMethod('request');
      state = result ? authorized : denied;
    }
    if (state != authorized) {
      throw PlatformException(code: 'NO ACCESS');
    }
    // Start camera.
    final answer =
        await method.invokeMapMethod<String, dynamic>('start', facing.index);
    final textureId = answer['textureId'];
    final size = toSize(answer['size']);
    viewArgs.value = CameraViewArgs(textureId, size);
    torchable = answer['torchable'];
  }

  @override
  void stop() {
    method.invokeMethod('stop');
  }

  @override
  void torch() {
    if (!torchable) {
      return;
    }
    var state =
        torchState.value == TorchState.off ? TorchState.on : TorchState.off;
    method.invokeMethod('torch', state.index);
  }

  @override
  void dispose() {
    subscription.cancel();
    barcodesController.close();
    torchState.dispose();
    viewArgs.dispose();
  }
}
