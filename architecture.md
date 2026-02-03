# 🏗️ D-Linker 技術架構

## 1. 帳戶推導邏輯 (Identity Derivation)
- **Seed**: `ANDROID_ID` (唯一硬體識別碼)
- **Salt**: `D-Linker-Hardware-Anchor-2023` (防止 Rainbow Table 攻擊)
- **Algo**: `SHA-256`
- **Output**: 40-char Hex (Ethereum style address)

## 2. 金鑰管理 (Key Management)
- **Address**: 由 `ANDROID_ID` 確定性生成。
- **Private Key**: 
  - 儲存位置：Android KeyStore (硬件隔離區)。
  - 目的：僅用於「離線簽名」交易。
  - 安全規範：私鑰永不離開設備，永不備份到雲端。

## 3. 中繼轉發 (Relay Service)
- **Platform**: Firebase Cloud Functions
- **Flow**: 
  1. Android App 發起轉帳請求 + 數位簽名。
  2. Cloud Functions 接收請求。
  3. Cloud Functions 調用管理員錢包代付 Gas 費並廣播至 Base Sepolia。
  4. 交易成功後更新 Firestore 緩存。

## 4. 區塊鏈層 (Blockchain)
- **Network**: Base Sepolia (Ethereum L2 Testnet)
- **Asset**: ERC-20 Token (D-Linker Token)
