import 'package:flutter/services.dart';

const method = MethodChannel('yanshouwang.dev/camerax/method');
const event = EventChannel('yanshouwang.dev/camerax/event');

final stream = event.receiveBroadcastStream();
