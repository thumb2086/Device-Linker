# 📋 D-Linker 開發待辦清單

## 第二階段：Firebase 中繼站開發 (核心邏輯期)
- [x] **中繼函數**：實作 `requestAirdrop` 雲端函數。
- [x] **環境變數配置**：已確認需修正 `project_id` 命名規範。
- [x] **Vercel API 補全**：
    - [x] 修正 `airdrop.js` 憑證欄位。
    - [ ] 新增 `get-balance.js` (連帶影響：需手動同步至 Vercel Repo)。
    - [ ] 新增 `transfer.js` (連帶影響：需手動同步至 Vercel Repo)。

## 第三階段：Android 原生開發 (前端整合期)
- [x] **Firebase 整合**：已由 SDK 轉向 Vercel API 呼叫。
- [x] **API 端點對齊**：已修正為 `/api/airdrop`。
