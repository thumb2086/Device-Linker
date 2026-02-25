import 'package:flutter_test/flutter_test.dart';
import 'package:device_linker_flutter/main.dart';

void main() {
  testWidgets('App boots into dashboard shell', (WidgetTester tester) async {
    await tester.pumpWidget(const DeviceLinkerApp());
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.textContaining('D-Linker'), findsAtLeastNWidgets(1));
    expect(find.byType(Scaffold), findsWidgets);
  });
}
