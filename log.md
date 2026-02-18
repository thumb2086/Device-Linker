# 📜 D-Linker 開發日誌

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
