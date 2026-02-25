import 'package:flutter_test/flutter_test.dart';
import 'package:device_linker_flutter/main.dart';

void main() {
  testWidgets('App starts and shows scaffold text', (WidgetTester tester) async {
    await tester.pumpWidget(const DeviceLinkerApp());

    expect(find.text('Device Linker (Flutter)'), findsOneWidget);
    expect(find.text('Multi-platform Flutter app scaffold is ready.'), findsOneWidget);
  });
}
