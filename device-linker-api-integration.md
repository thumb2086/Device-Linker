# Device-Linker API Integration

> **專案整合說明**
> 此 API 由 [zixi-casino](https://github.com/thumb2086/zixi-casino) 專案的 `apps/api/` 提供。
> zixi-wallet-backend 已遷移合併至 zixi-casino。

Base URL: `https://zixi-casino.vercel.app/api/`

## Endpoint Overview
- `POST /api/user`
- `POST /api/wallet`
- `POST /api/stats`
- `POST /api/admin`
- `POST /api/game?game=<gameId>`
- `POST /api/market-sim`

## 1) Hardware Authorization
### Create pending session
`POST /api/user`
```json
{
  "action": "create_session",
  "ttlSeconds": 600,
  "platform": "android",
  "clientType": "mobile",
  "deviceId": "dlinker_xxx",
  "appVersion": "1.0.0+1"
}
```

### Authorize session from Device-Linker app
`POST /api/user`
```json
{
  "action": "authorize",
  "sessionId": "session_xxx",
  "address": "0x1234...",
  "publicKey": "<base64-spki>",
  "platform": "android",
  "clientType": "mobile",
  "deviceId": "dlinker_xxx",
  "appVersion": "1.0.0+1"
}
```

### Poll authorization status
- `GET /api/user?action=get_status&sessionId=session_xxx`
- or `POST /api/user` with:
```json
{
  "action": "get_status",
  "sessionId": "session_xxx"
}
```

## 2) Custody Login
`POST /api/user`
```json
{
  "action": "custody_login",
  "username": "demo_user",
  "password": "secret123",
  "platform": "android",
  "clientType": "mobile",
  "deviceId": "dlinker_xxx",
  "appVersion": "1.0.0+1"
}
```

## 3) Wallet / Balance / Summary / History
### Get wallet balance
`POST /api/wallet`
```json
{
  "action": "get_balance",
  "address": "0x1234..."
}
```

### Get wallet summary
`POST /api/wallet`
```json
{
  "action": "summary",
  "sessionId": "session_xxx"
}
```

### Get game settlement history
`POST /api/wallet`
```json
{
  "action": "game_history",
  "sessionId": "session_xxx",
  "limit": 12
}
```

Notes:
- `betAmount` is the stake for that round.
- `payoutAmount` is the amount transferred back to player wallet for settlement.
- `netAmount` is real wallet delta for that round; negative means loss.

## 4) Transfer History
`POST /api/user`
```json
{
  "action": "get_history",
  "address": "0x1234...",
  "page": 1,
  "limit": 20
}
```

Notes:
- Depends on server-side `ETHERSCAN_API_KEY`.
- If missing in Vercel, history may fail even when client code is correct.

## 5) Secure Transfer
`POST /api/wallet`
```json
{
  "action": "secure_transfer",
  "sessionId": "session_xxx",
  "from": "0x1234...",
  "to": "0xabcd...",
  "amount": "10",
  "signature": "<base64-der-signature>",
  "publicKey": "<base64-spki>"
}
```

Signature message format:
`transfer:<to_without_0x_lowercase>:<amount>`

Example:
`transfer:abcd1234abcd1234abcd1234abcd1234abcd1234:10`

## 6) Airdrop
`POST /api/wallet`
```json
{
  "action": "airdrop",
  "sessionId": "session_xxx",
  "address": "0x1234..."
}
```

Notes:
- Flutter should send `address` / `from` together with `sessionId` for wallet actions.
- Backend `api/wallet.js` uses them as a fallback when KV session replication is briefly delayed right after authorization.

## 7) Leaderboards
### Total bet leaderboard
`POST /api/stats`
```json
{
  "action": "total_bet",
  "sessionId": "session_xxx",
  "limit": 50
}
```

### Net worth leaderboard
`POST /api/stats`
```json
{
  "action": "net_worth",
  "sessionId": "session_xxx",
  "limit": 50
}
```

## 8) Coinflip
`POST /api/game?game=coinflip&sessionId=session_xxx`
```json
{
  "action": "bet",
  "address": "0x1234...",
  "amount": "10",
  "sessionId": "session_xxx",
  "choice": "heads",
  "gameId": "coinflip",
  "signature": "<base64-der-signature>",
  "publicKey": "<base64-spki>"
}
```

## 9) Market Simulation & Stock Trading
`POST /api/market-sim`

Common request fields:
- `sessionId`: "session_xxx"
- `action`: "snapshot" | "bank_deposit" | "bank_withdraw" | "borrow" | "repay" | "buy_stock" | "sell_stock" | "open_futures" | "close_futures"

Examples:
```json
{ "action": "bank_deposit", "sessionId": "session_xxx", "amount": 100 }
{ "action": "buy_stock", "sessionId": "session_xxx", "symbol": "BTC", "quantity": 1.5 }
{ "action": "open_futures", "sessionId": "session_xxx", "symbol": "BTC", "side": "long", "margin": 100, "leverage": 10 }
{ "action": "close_futures", "sessionId": "session_xxx", "positionId": "pos_123" }
```

Response includes `account`, `market`, `vipLevel`, `maxBet` and `actionResult`.

Notes:
- This route belongs to the 子熙模擬器 / market simulator flow, not the Device-Linker wallet app UI.

## Device-Linker App Mapping
- Hardware authorize: `flutter_app/lib/main.dart`
- Balance: `flutter_app/lib/main.dart`
- History: `flutter_app/lib/main.dart`
- Transfer: `flutter_app/lib/main.dart`

Server handlers:
- User auth/history: `api/user.js`
- Wallet/balance/transfer: `api/wallet.js`
- Stats: `api/stats.js`
