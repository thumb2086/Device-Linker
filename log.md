# 📜 D-Linker 開發日誌

---
## [2023-10-28] Phase 2: Firebase Relay Service - RequestAirdrop Implementation
**執行內容 (Phase 2: Firebase):**
- **環境初始化**: 建立 `functions/` 目錄，配置 `package.json` 並安裝 `ethers` 庫。
- **中繼函數開發**: 實作 `requestAirdrop` Cloud Function，連結合約地址 `0x531aa...9142`。
- **邏輯實現**: 該函數接收 Android 端傳入的設備錢包地址，並由 Cloud Functions 使用管理員私鑰呼叫合約 `mintTo` 函數，發放 100 DLINK 測試代幣。
- **安全性設計**: 採用 Firebase Secrets 管理 `ADMIN_PRIVATE_KEY`，確保私鑰不外洩。

**後續步驟:**
- **部署**: 需要在終端機執行 `firebase deploy` 並設定 Secret 變數。
- **整合**: 下一步將在 Android 端實作對此 Cloud Function 的呼叫。

---
## [2023-10-28] Phase 1: Smart Contract & Deployment Setup (Completed via Remix)
**執行內容 (Phase 1: Blockchain):**
- **線上部署**: 使用者已透過 Remix IDE 完成 `DLinkerToken.sol` 部署。
- **合約資訊**: 地址為 `0x531aa0c02ee61bfdaf2077356293f2550a969142` (Base Sepolia)。
- **代碼同步**: 已同步本地 Solidity 代碼，包含 `mintTo` 與 `initialMint` 函數。

---
## [2023-10-27] Implementation of Deterministic Address Derivation
**執行內容 (路徑 B: New Feature):**
- **Android**: 更新 `MainActivity.kt`，實作 `ANDROID_ID` 讀取邏輯。
- **Logic**: 實作 `deriveAddress` 函數，使用 SHA-256 + Salt 從 `ANDROID_ID` 推導出確定性的錢包地址。
- **UI**: 更新 Compose UI 以顯示 Hardware ID 與推導出的 Wallet Address。

**D-Linker 安全檢查:**
- **硬體綁定**: 已驗證 `ANDROID_ID` 作為地址種子，確保設備與地址的一一對應。
- **私鑰安全**: 目前僅推導地址，尚未涉及私鑰存儲。下一步將引入 KeyStore 進行優化。
...
