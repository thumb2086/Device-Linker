# Flutter Multi-Platform App

This folder is now a functional Flutter migration of the original Android app.

## Migration scope (implemented)
- Hardware-bound wallet key (local secure storage)
- Wallet address derivation
- Auth / coinflip / airdrop / transfer / history API integration
- Dashboard with balance auto-refresh and manual refresh
- Receive QR display
- QR scanner flow (auth / bet / transfer parsing)
- Manual auth code entry fallback
- Device migration flow (transfer all balance)
- Contacts management (add/delete/select)
- Transaction history with pagination
- Balance increase local notifications
- Deep-link handling for `dlinker:` login session links
- Language switch (System / zh-TW / zh-CN / en)

## Important notes
- Existing Android project files remain unchanged.
- Existing old CI/CD workflows remain unchanged.
- New Flutter release workflow is in `.github/workflows/flutter-multiplatform-build.yml`.

## Local bootstrap
```bash
cd flutter_app
flutter create . --project-name device_linker_flutter --org com.devicelinker --platforms=android,ios,web,linux,windows
flutter pub get
flutter run
```
