🤖 D-Linker 專案專屬 Agent 工作流程 (v1.0)
此工作流程核心：確保「Android 硬體端」與「Firebase 雲端端」的數據一致性，並嚴格監控區塊鏈交易的非同步狀態。

🛑 數據完整性與檔案範圍 (SCOPE & INTEGRITY)
全棧檔案支持：

Android (Frontend): .kt, .xml, build.gradle.kts, AndroidManifest.xml

Firebase (Backend): functions/index.js, package.json, .firebaserc, firestore.rules

Blockchain: contracts/*.sol, hardhat.config.js (或 Remix 導出文件)

文件系統: log.md, todo.md, architecture.md

log.md 歷史保護： 絕對禁止覆蓋舊紀錄，一律採用 頂部插入 (Prepending)，確保開發軌跡可回溯。

todo.md 狀態同步： * 執行前清理 [x]。

新增規範： 若涉及跨端修改（如：修改 Android 同時需修改 Firebase），必須在 todo.md 以子項目列出連帶影響。

🔍 跨端除錯與邏輯檢查 (Cross-Platform Debugging)
當工具回報 "Nothing to report" 但功能異常時：
Agent 必須立即啟動「D-Linker 深蹲模式」：

硬體金鑰校驗： 手動檢查 ANDROID_ID 到私鑰的推導路徑。

檢查點： Salt 值是否一致？加密演算法是否在 Android 不同版本間有相容性問題？

Firebase 參數對比： 檢查 Android 呼叫 HttpsCallable 的參數名稱與 Cloud Functions 接收的 data 鍵值是否完全對應。

區塊鏈非同步檢查： 手動分析代碼中是否漏寫了 await tx.wait() 或處理 Transaction Reverted 的異常邏輯。

邏輯判斷流程 (Logic Flow)
1. Mode A: 規劃與維護 (Architect & Plan)
   新增： 確認修改是否涉及 智慧合約 ABI 變更。若是，必須自動檢查 Android 與 Firebase 的合約地址與 ABI 檔案是否已同步更新。

2. Mode B: 開發、除錯與執行 (Develop, Debug & Execute)
   Phase 1: 清理與路徑確認
   讀取 todo.md 並清理舊項。

驗證檔案路徑： 區分 app/src/main/... (Android) 與 functions/... (Firebase)。

Phase 2: 任務性質判斷 (CRITICAL DECISION)
路徑 A：修復 Bug (如：交易失敗、餘額不顯示)

執行 inspect_code。

若回報 0 錯誤： 檢查 Firebase Logs 是否有隱藏報錯，或 Base Sepolia 測試網是否有網路延遲。

路徑 B：新增功能 (如：一鍵搬家、新手印幣)

❌ 無需等待 Linter 報錯。

✅ 直接實作： 修改 Kotlin 代碼或 Cloud Functions。

安全性檢查： 確保 管理員私鑰 絕未出現在 Android 端，且 Firebase 安全規則 (Security Rules) 已開啟。

Phase 3: 結案
更新 log.md (頂部插入)：

必須記錄：Transaction Hash (若有測試)、Contract Address 變動、或新的 Firebase Function 名稱。

更新 todo.md： 打勾完成。

🤖 專屬除錯指令：當自動化失效時 (針對 D-Linker)
若任務為「除錯」且 inspect 沒發現問題，Agent 必須強制手動分析：

「我發現 Android 端的 BigInteger 轉換與 Solidity 的 uint256 精度不符，這會導致轉帳金額錯誤。」

「Firebase 的 admin_key 讀取失敗，可能是 functions.config() 未正確設定。」

「一鍵搬家邏輯中，未處理 Gas 費預留問題。」

狀態回報範本 (D-Linker 專用)
"✅ D-Linker 任務執行完畢

執行內容 (路徑 B: New Feature):

Android: 修改 MigrationActivity.kt，實作全額轉出邏輯。

Firebase: 在 relayTransfer 函數中新增了權限檢查。

D-Linker 安全檢查:

硬體綁定: 已確認 ANDROID_ID 讀取邏輯不受影響。

私鑰安全: 已確認私鑰僅存在於本地加密儲存與雲端環境變數中。

文件同步:

todo.md: 本次任務 [x]。

log.md: 已在頂部追加紀錄，包含本次更新的 API 版本。

請檢查變動。"