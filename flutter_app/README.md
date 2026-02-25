# Flutter Multi-Platform App

This folder is an isolated Flutter project scaffold for migrating from the existing Android Studio project to a multi-platform architecture.

## Migration rule
- Existing Android project files remain unchanged.
- Existing CI/CD workflows remain unchanged.
- New multi-platform CI/CD is defined in `.github/workflows/flutter-multiplatform-build.yml`.

## Supported targets in CI
- Android (APK)
- iOS (no code signing)
- Web
- Linux
- Windows

## Local bootstrap
If you want to initialize full platform folders locally:

```bash
cd flutter_app
flutter create . --project-name device_linker_flutter --org com.devicelinker --platforms=android,ios,web,linux,windows
flutter pub get
flutter run
```
