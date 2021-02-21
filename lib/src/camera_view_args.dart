import 'package:flutter/material.dart';

/// Camera args for [CameraView].
class CameraViewArgs {
  /// The texture id.
  final int textureId;

  /// Size of the texture.
  final Size size;

  /// Create a [CameraViewArgs].
  CameraViewArgs(this.textureId, this.size);
}
