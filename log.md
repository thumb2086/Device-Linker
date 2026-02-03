# 📜 D-Linker 開發日誌

---
## [2023-10-27] Implementation of Deterministic Address Derivation
**執行內容 (路徑 B: New Feature):**
- **Android**: 更新 `MainActivity.kt`，實作 `ANDROID_ID` 讀取邏輯。
- **Logic**: 實作 `deriveAddress` 函數，使用 SHA-256 + Salt 從 `ANDROID_ID` 推導出確定性的錢包地址。
- **UI**: 更新 Compose UI 以顯示 Hardware ID 與推導出的 Wallet Address。

**D-Linker 安全檢查:**
- **硬體綁定**: 已驗證 `ANDROID_ID` 作為地址種子，確保設備與地址的一一對應。
- **私鑰安全**: 目前僅推導地址，尚未涉及私鑰存儲。下一步將引入 KeyStore 進行優化。

---
## [2023-10-27] Project Initialization & Android Scaffolding
**執行內容 (路徑 B: New Feature):**
- **Android**: 建立基礎專案結構 (settings.gradle.kts, build.gradle.kts, AndroidManifest.xml)。
- **UI**: 實作 `activity_main.xml`，預留硬體 ID 與錢包地址顯示區域。
- **Logic**: 在 `MainActivity.kt` 實作基於 `ANDROID_ID` 的 `deriveAddress` 確定性推導算法 (SHA-256 + Salt)。
- **Docs**: 初始化 `todo.md` 與 `log.md`。

**D-Linker 安全檢查:**
- **硬體綁定**: 成功讀取 `ANDROID_ID` 作為身分種子，確保地址唯一性。
- **私鑰安全**: 目前僅實作地址推導，私鑰尚未生成/儲存。
