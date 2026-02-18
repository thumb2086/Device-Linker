# 📜 D-Linker 開發日誌

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
