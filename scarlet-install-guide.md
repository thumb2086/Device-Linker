# Device-Linker iOS 安裝說明（Scarlet）

本版本提供 `flutter-ios-unsigned.ipa`，需透過 Scarlet 重簽後安裝。

## 安裝步驟
1. 從 GitHub Releases 下載 `flutter-ios-unsigned.ipa`。
2. 在 iPhone 安裝 Scarlet（依 Scarlet 官方教學）。
3. 開啟 Scarlet，選擇 `Import` 或 `Install IPA`。
4. 選取剛下載的 `flutter-ios-unsigned.ipa`。
5. 等待 Scarlet 完成重簽與安裝。
6. 回到 iPhone 主畫面開啟 Device-Linker。

## 第一次開啟失敗處理
1. 打開 `設定 > 一般 > VPN 與裝置管理`。
2. 找到對應開發者憑證並點擊「信任」。
3. 再次開啟 App。

## 常見問題
### Q1: App 會閃退或突然打不開
- 可能是重簽憑證失效或被撤銷。
- 解法：重新用 Scarlet 重簽安裝最新版 IPA。

### Q2: 更新後要重新登入
- 若重簽造成 Bundle ID 變動，舊 Keychain 資料可能不可讀。
- 解法：重新授權/登入一次。

### Q3: 為什麼不能直接安裝 unsigned IPA
- iOS 安裝到實機前一定要簽名。
- `unsigned.ipa` 只能作為重簽輸入檔，不能直接安裝。

## 給客服的快速回覆
- 「請先確認已在 `VPN 與裝置管理` 信任憑證，再重新開啟 App。」
- 「若還是打不開，請重新匯入最新 `flutter-ios-unsigned.ipa` 進 Scarlet 重簽安裝。」
