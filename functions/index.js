require('dotenv').config();
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const CONTRACT_ADDRESS = "0x531aa0c02ee61bfdaf2077356293f2550a969142";
const RPC_URL = "https://sepolia.base.org";

async function updateUserBalance(address) {
    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function balanceOf(address account) external view returns (uint256)",
            "function decimals() external view returns (uint8)"
        ], provider);

        const [balance, decimals] = await Promise.all([
            contract.balanceOf(address),
            contract.decimals()
        ]);
        const formattedBalance = ethers.formatUnits(balance, decimals);

        await db.collection('users').doc(address).set({
            balance: formattedBalance,
            lastUpdated: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        return formattedBalance;
    } catch (e) {
        console.error("Balance Update Error:", e);
        return "0";
    }
}

/**
 * requestAirdrop: 加入簽名驗證 (可選) 與 模擬器優化
 */
exports.requestAirdrop = functions.runWith({
    timeoutSeconds: 60, // 增加超時時間處理區塊鏈交易
    secrets: ["ADMIN_PRIVATE_KEY"]
}).https.onCall(async (data, context) => {
    const { address, publicKey, signature } = data;

    if (!ethers.isAddress(address)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址');
    }

    // 在本地模擬器中優先使用 .env 裡的私鑰
    const privateKey = process.env.ADMIN_PRIVATE_KEY || process.env.FUNCTIONS_EMULATOR ? process.env.ADMIN_PRIVATE_KEY : null;

    if (!privateKey) {
        console.error("Missing ADMIN_PRIVATE_KEY");
        throw new functions.https.HttpsError('internal', '伺服器私鑰未配置');
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function mintTo(address to, uint256 amount) external"
        ], wallet);

        console.log(`Processing airdrop for ${address}...`);

        // 執行 Mint
        const tx = await contract.mintTo(address, ethers.parseUnits("100", 18));
        console.log("Transaction sent:", tx.hash);

        // 如果有傳入公鑰，順便更新
        if (publicKey) {
            await db.collection('users').doc(address).set({ publicKey }, { merge: true });
        }

        // 等待交易上鏈 (在模擬器環境下這可能很慢)
        await tx.wait();

        const newBalance = await updateUserBalance(address);

        return {
            success: true,
            txHash: tx.hash,
            balance: newBalance,
            message: "新手入金成功！"
        };
    } catch (error) {
        console.error("Airdrop Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});

// transfer 保持不變 (或根據需要調整)
exports.transfer = functions.runWith({ secrets: ["ADMIN_PRIVATE_KEY"] }).https.onCall(async (data, context) => {
    const { from, to, amount, signature } = data;
    // ... 保持原有邏輯
});
