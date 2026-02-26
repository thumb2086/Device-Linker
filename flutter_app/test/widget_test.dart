import 'package:device_linker_flutter/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('App boots with Material shell', (WidgetTester tester) async {
    await tester.pumpWidget(const DeviceLinkerApp());
    await tester.pump();

    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
