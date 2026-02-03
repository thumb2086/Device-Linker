require('dotenv').config(); // 載入 .env 文件

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");

admin.initializeApp();
const db = admin.firestore();

// 合約資訊 (由使用者提供)
const CONTRACT_ADDRESS = "0x531aa0c02ee61bfdaf2077356293f2550a969142";
const RPC_URL = "https://sepolia.base.org";

// 簡化版的 ABI，只需包含我們需要的函數
const ABI = [
    "function mintTo(address to, uint256 amount) external",
    "function balanceOf(address account) external view returns (uint256)",
    "function decimals() external view returns (uint8)"
];

/**
 * updateUserBalance: 內部函數，用於讀取鏈上餘額並更新 Firestore
 */
async function updateUserBalance(address) {
    const provider = new ethers.JsonRpcProvider(RPC_URL);
    const contract = new ethers.Contract(CONTRACT_ADDRESS, ABI, provider);

    const balance = await contract.balanceOf(address);
    const decimals = await contract.decimals();
    const formattedBalance = ethers.formatUnits(balance, decimals);

    await db.collection('users').doc(address).set({
        balance: formattedBalance,
        lastUpdated: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    return formattedBalance;
}

/**
 * requestAirdrop: 新手入金功能
 */
exports.requestAirdrop = functions.runWith({ secrets: ["ADMIN_PRIVATE_KEY"] }).https.onCall(async (data, context) => {
    const targetAddress = data.address;

    if (!ethers.isAddress(targetAddress)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址');
    }

    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!privateKey) {
        throw new functions.https.HttpsError('internal', '伺服器私鑰未配置');
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, ABI, wallet);

        const amount = ethers.parseUnits("100", 18);
        const tx = await contract.mintTo(targetAddress, amount);
        await tx.wait();

        // 同步餘額到 Firestore
        const newBalance = await updateUserBalance(targetAddress);

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

/**
 * syncBalance: 手動同步餘額
 */
exports.syncBalance = functions.https.onCall(async (data, context) => {
    const targetAddress = data.address;
    if (!ethers.isAddress(targetAddress)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址');
    }

    try {
        const newBalance = await updateUserBalance(targetAddress);
        return {
            success: true,
            balance: newBalance
        };
    } catch (error) {
        console.error("Sync Balance Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});
