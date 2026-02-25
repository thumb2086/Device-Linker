# Flutter Multi-Platform App

This folder is an isolated Flutter project for the Device-Linker migration.

## Migration rule
- Existing Android project files remain unchanged.
- Existing CI/CD workflows remain unchanged.
- New multi-platform release workflow is in `.github/workflows/flutter-multiplatform-build.yml`.

## Migrated features
- Hardware-bound local wallet key generation and address derivation
- Balance sync + deposit notification
- Test coin airdrop request
- Transfer and full-device migration flow
- QR receive address display
- QR scanner + manual code input
- Deep link auth flow (`dlinker:login:*`, `dlinker://login/*`)
- Coin flip signature request flow (`dlinker:coinflip:*`)
- Transaction history pagination
- Local contacts management and picker
- Language switching (system / zh-TW / zh-CN / en)

## Local bootstrap
```bash
cd flutter_app
flutter create . --project-name device_linker_flutter --org com.devicelinker --platforms=android,ios,web,linux,windows
flutter pub get
flutter run
```
