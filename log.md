# 📜 D-Linker 開發日誌

---
## [2026-02-18] Architecture Update: On-chain Data Drive
**執行內容 (Path A: Bug Fix):**
- **餘額查詢**: 發現 `get-balance.js` 讀取 Firestore 導致數據滯後，已重寫為直接透過 RPC 查詢合約 `balanceOf`。
- **一致性**: 徹底解決「鏈上有錢但 App 顯示 0.0」的脫節問題。
- **路徑對齊**: 確認 Android 端已對齊 `request-airdrop` 端點。

---
## [2026-02-18] Fix: Airdrop API Endpoint Mismatch & Stability Update
... (後續內容保持不變)
