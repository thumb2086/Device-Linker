# 📋 D-Linker 開發待辦清單

## 第一階段：區塊鏈與合約部署 (設計期)
- [x] **節點配置**：設定 Base Sepolia RPC 連線環境。
- [x] **合約開發**：完成具備 `mint` 與 `transfer` 功能的智能合約。
- [x] **線上部署**：合約已部署至 `0x531aa0c02ee61bfdaf2077356293f2550a969142` (Base Sepolia)。

## 第二階段：Firebase 中繼站開發 (核心邏輯期)
- [x] **中繼函數**：實作 `requestAirdrop` 雲端函數，對接合約 `mintTo` 功能。
- [x] **環境變數配置**：由於不使用 Blaze 方案，改用 `.env` 檔案配置管理員私鑰（需確保 `.gitignore` 已排除）。
- [x] **資料同步**：建立 Firestore 監聽邏輯，即時反應餘額變動 (Next step)。
- [x] **GitHub CICD**：完成自動化部署設定。

## 第三階段：Android 原生開發 (前端整合期)
- [x] **環境初始化**：建立 Android 專案基礎結構。
- [x] **硬體 ID 讀取**：實作基於 `ANDROID_ID` 的讀取邏輯。
- [x] **金鑰生成器 (Refinement)**：將地址推導邏輯從 MainActivity 抽離至獨立模組，並引入 Android KeyStore。
- [x] **Firebase 整合**：於 App 內導入 Firebase SDK 並對接 Cloud Functions。
- [x] **UI 實作**：完成資產看板、收款 QR Code 顯示、以及掃描器功能。
- [x] **簽名引擎**：實作本地端離線簽名 (Offline Signing) 功能。
