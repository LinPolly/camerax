import 'package:camerax/src/messenger.dart' as messenger;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final method = MethodChannel(messenger.method.name);

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    method.setMockMethodCallHandler((MethodCall methodCall) async {
      return '42';
    });
  });

  tearDown(() {
    method.setMockMethodCallHandler(null);
  });

  test('Camera ', () {});
}
