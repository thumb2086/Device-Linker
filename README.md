# Device-Linker

Device-Linker 是一個以 Flutter 為核心的多平台錢包應用專案，搭配 Firebase Functions 與 EVM 鏈上合約，提供裝置導向的地址管理、簽名與轉帳流程。

## Repo 狀態
- 已完成從舊版 Android Kotlin 專案遷移到 Flutter-first 架構。
- 舊 Android 原生模組與根目錄 Gradle 工程已移除。

## 目錄結構
- `flutter_app/`: Flutter 應用程式（主要前端）
- `functions/`: Firebase Cloud Functions（中繼/API）
- `blockchain/`: 合約與鏈上腳本
- `.github/workflows/`: CI/CD 流程

## 技術棧
- Flutter / Dart
- Firebase Functions
- Base Sepolia（EVM 測試鏈）
- ERC-20 合約

## 快速開始

### 1) Flutter App
```bash
cd flutter_app
flutter create . --project-name device_linker_flutter --org com.devicelinker --platforms=android,ios,web,linux,windows
flutter pub get
flutter analyze
flutter test
flutter run
```

### 2) Firebase Functions
```bash
cd functions
npm install
firebase deploy --only functions
```

## 開發流程建議
1. 先在 `flutter_app` 完成 UI 與簽名/交易流程。
2. 若 API 規格變更，同步更新 `functions/index.js`。
3. 若合約介面變更，同步更新 `blockchain/` 與前後端呼叫參數。

## CI/CD
- `flutter-multiplatform-build.yml`: Flutter 多平台建置與釋出流程
- `deploy-functions.yml`: Firebase Functions 部署流程

## 遷移備註
- 本 repo 目前不包含 Flutter 自動產生的平台殼層目錄（如 `flutter_app/android`、`flutter_app/ios`）。
- 需要時可透過 `flutter create . --platforms=...` 重新生成。

## 授權
本專案授權方式請依組織政策或另行補上 LICENSE 檔案。
