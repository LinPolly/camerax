import 'package:flutter/widgets.dart';

import 'camera_view_args.dart';

/// A widget showing a live camera preview.
class CameraView extends StatelessWidget {
  final ValueNotifier<CameraViewArgs> args;

  CameraView(this.args);

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: args,
      builder: (context, CameraViewArgs args, child) {
        if (args == null) {
          return Container(color: Color.fromARGB(255, 0, 0, 0));
        } else {
          return ClipRect(
            child: Transform.scale(
              scale: args.size.fill(MediaQuery.of(context).size),
              child: Center(
                child: AspectRatio(
                  aspectRatio: args.size.aspectRatio,
                  child: Texture(textureId: args.textureId),
                ),
              ),
            ),
          );
        }
      },
    );
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
