# 📜 D-Linker 開發日誌

---
## [2026-03-02] Docs: Scarlet Re-sign Install Guide
**執行內容 (User Support):**
- 新增 `scarlet-install-guide.md`，提供 iOS 使用者用 Scarlet 安裝 `flutter-ios-unsigned.ipa` 的完整流程。
- 補充首次開啟失敗處理與常見問題（憑證信任、憑證失效重簽、重簽後需重新登入）。
- 新增客服可直接複製的快速回覆內容，降低安裝支援成本。

---
## [2026-03-02] CI Update: iOS Unsigned IPA Artifact for Re-sign Flow
**執行內容 (Release Pipeline):**
- 調整 `.github/workflows/flutter-multiplatform-build.yml` iOS 產物由 `flutter-ios.zip` 改為 `flutter-ios-unsigned.ipa`。
- iOS 打包流程改為建立 `Payload/Runner.app` 再壓成 `.ipa`，可直接提供重簽工具使用。
- 文件同步：
  - `README.md` 新增 CI iOS unsigned IPA 說明
  - `flutter_app/README.md` 新增 iOS 產物用途說明（需重簽/簽名）
- `todo.md` 勾選「CI 輸出 iOS unsigned .ipa」完成項目。

---
## [2026-03-02] iOS Build Bootstrap: Platform Shell + Device Install Guidance
**執行內容 (Build Reliability):**
- **根因確認**: `flutter_app/ios` 未提交，導致本地無法直接執行 iOS 編譯與安裝流程。
- **腳本補齊**: 新增 `flutter_app/scripts/bootstrap_ios.sh`，提供 iOS 一鍵初始化：
  - 檢查 `flutter` 是否可用
  - 限制 iOS build 需在 macOS 執行
  - 執行 `flutter create` 生成 `ios/`
  - 執行 `flutter pub get` 與 `flutter build ios --release --no-codesign`
- **文件同步**:
  - `flutter_app/README.md` 新增 iOS 章節（生成平台殼層與實機安裝說明）
  - `README.md` 新增 iOS 實機安裝注意（需 Xcode 簽名並匯出 `.ipa`）
- **待辦更新**: `todo.md` 新增「建立 iOS 簽名與 `.ipa` 匯出流程」項目，作為可安裝實機版本的下一步。

---
## [2026-02-26] Auth API Alignment: Multi-Platform Session Metadata + Validation
**執行內容 (API Contract Sync):**
- **授權參數升級**: Flutter `DLinkerApi.sendAuth` 新增送出 `platform`、`clientType`、`deviceId`、`appVersion`，對齊後台多平台授權規格。
- **新流程方法補齊**:
  - 新增 `createPendingAuthSession()` 對應 `POST /api/v3/auth/create`（含 TTL 驗證 60-3600 秒，預設 600 秒）。
  - 新增 `getAuthStatus()` 對應 `GET /api/auth?sessionId=...`。
- **輸入驗證補強**:
  - 加入 `sessionId` 格式驗證與掃碼/深連結 session normalize。
  - 地址先正規化驗證（相容 `ethers.getAddress` 檢查邏輯）再以小寫送出以保留舊流程。
  - `publicKey` 增加長度上限防呆。
- **裝置識別補齊**: 新增 `AppStorage.getDeviceId()`，首次產生並持久化 `deviceId`。
- **文件同步**: `README.md`、`architecture.md`、`agent.md` 更新為 REST 後台為主，Firebase 標示為 legacy。

---
## [2026-02-26] Repo Cleanup: Android Kotlin Legacy Removal
**執行內容 (Repository Refactor):**
- 移除舊版 Android Kotlin 模組 `app/`。
- 移除根目錄舊 Gradle 結構：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/`、`gradlew*`。
- 更新專案文件為 Flutter-first：`README.md`、`architecture.md`、`agent.md`、`todo.md`。
- 調整 `.gitignore`，改為 Flutter 專案與平台產物為主。


---
## [2026-02-18] Feature Update: Deep Link & Manual Auth Code Support
**執行內容 (Path B: New Feature):**
- **Deep Link 支援**:
    - 修改 `AndroidManifest.xml` 增加 `intent-filter` 支援 `dlinker:login:<sessionId>` 與 `dlinker://login/<sessionId>`。
    - 在 `MainActivity` 實作 `onNewIntent` 與 `handleIntent` 解析 Deep Link。
- **手動貼碼功能**:
    - 在掃描器對話框新增「輸入授權碼」按鈕。
    - 實作 `ManualCodeDialog` 支援直接貼上 `sessionId` 或完整連結。
- **UI/UX 優化**:
    - 授權成功後顯示「授權成功，可返回網頁」提示，優化手機網頁登入體驗。
- **規格對齊**: 統一 normalize 各種來源的 `sessionId` 並呼叫 `DLinkerApi.sendAuth`。
- **編譯修復**: 修正 `MainActivity.kt` 中變數宣告順序錯誤導致的建置失敗。

---
## [2026-02-18] CI/CD Fix: Signing Configuration & Key Alias
**執行內容 (Path A: Bug Fix):**
- **簽署邏輯強化**: 修改 `app/build.gradle.kts`，增加 `isNullOrBlank()` 檢查，防止 CI 在 Secret (尤其是 `KEY_ALIAS`) 缺失時嘗試錯誤簽署。
- **問題診斷**: 確認 GitHub Actions 報錯 `No key with alias '' found` 係因 GitHub Secrets 漏設 `KEY_ALIAS` 所致。
- **修復建議**: 已指引使用者在 GitHub Repository Secrets 中補齊 `KEY_ALIAS`。

---
## [2026-02-18] Security & Protocol Update: Transfer with Public Key
**執行內容 (Path B: Protocol Upgrade):**
- **協議升級**: 修改 `FirebaseManager.transfer`，現在會額外傳送 `publicKey` (Base64) 給後端。
- **簽名對象加固**: 
    - 使用 `BigDecimal` 確保金額字串無 `.0` 或科學記號。
    - 強制執行 `trim()` 與 `lowercase()` 以排除不可見字元與大小寫不一致。
- **演算法同步**: Android 端全面切換至 `SHA256withECDSA`，順應硬體 TEE 行為。

---
## [2026-02-18] Architecture Update: On-chain Data Drive
... (後續內容保持不變)
