#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! command -v flutter >/dev/null 2>&1; then
  echo "Error: flutter command not found. Please install Flutter SDK first."
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Error: iOS build requires macOS + Xcode."
  exit 1
fi

echo "[1/4] Validate Flutter toolchain"
flutter doctor -v

echo "[2/4] Generate iOS platform shell (if missing)"
cd "${APP_DIR}"
flutter create . --project-name device_linker_flutter --org com.devicelinker --platforms=ios

echo "[3/4] Resolve dependencies"
flutter pub get

echo "[4/4] Build iOS release (no codesign)"
flutter build ios --release --no-codesign

echo
cat <<'MSG'
Build completed: build/ios/iphoneos/Runner.app

Note:
- This output is not directly installable on a physical iPhone.
- To install on device, open ios/Runner.xcworkspace in Xcode,
  set Team/Bundle ID/Provisioning Profile, then archive/export signed IPA.
MSG
