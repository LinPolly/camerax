import 'package:flutter/material.dart';

import 'camera_view_args.dart';
import 'camera.dart';

/// A widget showing a live camera preview.
class CameraView extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: camera.viewArgs,
      builder: (context, value, child) => _build(context, value),
    );
  }

  Widget _build(BuildContext context, CameraViewArgs value) {
    if (value == null) {
      return Container(color: Colors.black);
    } else {
      return ClipRect(
        child: Transform.scale(
          scale: value.size.fill(MediaQuery.of(context).size),
          child: Center(
            child: AspectRatio(
              aspectRatio: value.size.aspectRatio,
              child: Texture(textureId: value.textureId),
            ),
          ),
        ),
      );
    }
  }
}

extension on Size {
  double fill(Size targetSize) {
    if (targetSize.aspectRatio < aspectRatio) {
      return targetSize.height * aspectRatio / targetSize.width;
    } else {
      return targetSize.width / aspectRatio / targetSize.height;
    }
  }
}
