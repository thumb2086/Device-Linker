🤖 D-Linker 專案 Agent 工作流程（Flutter 版）

核心目標：維持「Flutter 前端」、「REST 後台 API」、「區塊鏈合約」三端資料一致。

🛑 檔案範圍 (Scope)
- Flutter (Frontend): `flutter_app/lib/**/*.dart`, `flutter_app/pubspec.yaml`
- Backend API (Primary): `flutter_app/lib/main.dart` (`DLinkerApi` endpoint/contract)
- Blockchain: `blockchain/contracts/*.sol`, `blockchain/scripts/*`
- 文件: `README.md`, `architecture.md`, `log.md`, `todo.md`

🔍 除錯重點 (Debug Focus)
1. 金鑰與簽名
- 檢查 `KeyService` 的地址推導與簽名是否與後端驗證規格一致。
2. API 參數一致性
- 檢查 Flutter 送出的欄位名稱與後台 REST API 接收欄位完全一致。
3. 區塊鏈非同步
- 檢查交易送出後是否正確等待確認並回寫狀態。

🧭 任務流程 (Execution)
1. 先讀 `todo.md`，確認待辦與優先級。
2. 修改 Flutter 時，優先同步更新 `DLinkerApi` 與 API 文件。
3. 完成後更新 `log.md`（新增紀錄，不覆蓋舊內容）。
4. 勾選 `todo.md` 完成項目。

✅ 完成標準
- 功能在 Flutter 端可觸發且流程完整。
- 後端 API 與交易結果可對應。
- 文件與待辦同步更新。
