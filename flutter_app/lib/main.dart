import 'package:flutter/material.dart';

void main() {
  runApp(const DeviceLinkerApp());
}

class DeviceLinkerApp extends StatelessWidget {
  const DeviceLinkerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Device Linker',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Device Linker (Flutter)'),
      ),
      body: const Center(
        child: Text('Multi-platform Flutter app scaffold is ready.'),
      ),
    );
  }
}
