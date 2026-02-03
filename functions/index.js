const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");

admin.initializeApp();

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
 * requestAirdrop: 新手入金功能
 * 由 Android App 呼叫，為新生成的硬體地址發放初始 100 DLINK
 */
exports.requestAirdrop = functions.https.onCall(async (data, context) => {
    const targetAddress = data.address;

    if (!ethers.isAddress(targetAddress)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址');
    }

    // 從環境變數讀取管理員私鑰
    // 注意：部署後需執行 firebase functions:secrets:set ADMIN_PRIVATE_KEY="你的私鑰"
    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!privateKey) {
        throw new functions.https.HttpsError('internal', '伺服器私鑰未配置');
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, ABI, wallet);

        // 鑄造 100 顆 DLINK (假設 decimals 是 18)
        const amount = ethers.parseUnits("100", 18);

        console.log(`正在為 ${targetAddress} 鑄造代幣...`);
        const tx = await contract.mintTo(targetAddress, amount);
        const receipt = await tx.wait();

        return {
            success: true,
            txHash: receipt.hash,
            message: "新手入金成功！"
        };
    } catch (error) {
        console.error("Airdrop Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});
