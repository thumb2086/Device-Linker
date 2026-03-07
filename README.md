# Device-Linker

Device-Linker 是一個以 Flutter 為核心的多平台錢包應用專案，搭配 REST 後台 API 與 EVM 鏈上合約，提供裝置導向的地址管理、授權、簽名與轉帳流程。

## Repo 狀態
- 已完成從舊版 Android Kotlin 專案遷移到 Flutter-first 架構。
- 舊 Android 原生模組與根目錄 Gradle 工程已移除。

## 目錄結構
- `flutter_app/`: Flutter 應用程式（主要前端）
- `functions/`: 舊版 Firebase Functions（legacy，已不作為主要後台）
- `blockchain/`: 合約與鏈上腳本
- `.github/workflows/`: CI/CD 流程

## 技術棧
- Flutter / Dart
- REST API（Vercel / Node.js）
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

### iOS 實機安裝注意
- 本 repo 預設不提交 `flutter_app/ios/`，需先生成平台殼層。
- 可執行 `flutter_app/scripts/bootstrap_ios.sh` 一次完成 iOS 初始化與 release build（no-codesign）。
- 要安裝到 iPhone，仍需在 macOS + Xcode 進行簽名與匯出 `.ipa`（設定 Team / Provisioning Profile）。

### 2) Backend API
- Flutter 端預設呼叫 `https://device-linker-api.vercel.app/api/`
- 簡化後端點:
  - `POST /api/user`：`create_session`、`authorize`、`get_status`、`custody_login`、`get_history`
  - `POST /api/wallet`：`get_balance`、`summary`、`secure_transfer`、`airdrop`
  - `POST /api/stats`：`total_bet`、`net_worth`
  - `POST /api/admin`
  - `POST /api/game?game=<gameId>`
  - `POST /api/market-sim`
- 授權狀態可用 `POST /api/user` 搭配 `action=get_status` 查詢，也可相容使用 `GET /api/user?action=get_status&sessionId=...`

## 開發流程建議
1. 先在 `flutter_app` 完成 UI 與簽名/交易流程。
2. 若 API 規格變更，同步更新 `flutter_app/lib/main.dart` 的 `DLinkerApi`。
3. 若合約介面變更，同步更新 `blockchain/` 與前後端呼叫參數。

## CI/CD
- `flutter-multiplatform-build.yml`: Flutter 多平台建置與釋出流程
- iOS 會輸出 `flutter-ios-unsigned.ipa`（未簽名），可供後續 Apple 簽名或第三方重簽工具使用
- `deploy-functions.yml`: 舊版 Firebase 部署流程（legacy）

## 遷移備註
- 本 repo 目前不包含 Flutter 自動產生的平台殼層目錄（如 `flutter_app/android`、`flutter_app/ios`）。
- 需要時可透過 `flutter create . --platforms=...` 重新生成。

## 授權
本專案授權方式請依組織政策或另行補上 LICENSE 檔案。
