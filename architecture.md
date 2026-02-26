# D-Linker 技術架構（Flutter）

## 1. 身分與金鑰層 (Identity & Key Management)
- **演算法**: `secp256k1`
- **私鑰來源**: App 首次啟動時以安全亂數產生
- **儲存策略**: 優先使用 `flutter_secure_storage`，失敗時回退到本地儲存
- **地址推導**: 由公鑰計算 Keccak-256，取後 20 bytes 形成 EVM 地址

## 2. 應用層 (Flutter App)
- **平台**: Flutter（Android / iOS / Web / Linux / Windows）
- **核心功能**: 錢包地址生成、餘額同步、轉帳、搬家轉移、QR 掃描、深連結授權
- **語系**: 系統語言 / zh-TW / zh-CN / en

## 3. 中繼服務層 (Relay Service)
- **平台**: REST Backend API（Vercel / Node.js）
- **流程**:
  1. Flutter App 送出簽名請求
  2. 後台驗證授權參數（含 `sessionId`、地址正規化、公鑰長度限制）
  3. 廣播交易到 Base Sepolia
  4. 回傳授權/交易狀態給多平台客戶端（Flutter / Web / Desktop）

## 4. 區塊鏈層 (Blockchain)
- **Network**: Base Sepolia
- **Asset**: ERC-20 (D-Linker Token)
- **目標**: 透明、可驗證、不可竄改的交易帳本
