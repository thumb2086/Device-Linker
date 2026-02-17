const { ethers } = require("ethers");
const admin = require("firebase-admin");

// 初始化 Firebase Admin (用於更新 Firestore)
if (!admin.apps.length) {
    admin.initializeApp({
        credential: admin.credential.cert({
            projectId: process.env.FIREBASE_PROJECT_ID,
            clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
            privateKey: process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, '\n'),
        })
    });
}

const db = admin.firestore();
const CONTRACT_ADDRESS = "0x531aa0c02ee61bfdaf2077356293f2550a969142";
const RPC_URL = "https://sepolia.base.org";

module.exports = async (req, res) => {
    // 限制只接受 POST 請求
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Method Not Allowed' });
    }

    const { address, publicKey } = req.body;

    if (!address || !ethers.isAddress(address)) {
        return res.status(400).json({ success: false, message: '無效的錢包地址' });
    }

    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!privateKey) {
        return res.status(500).json({ success: false, message: '伺服器私鑰未配置' });
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function mintTo(address to, uint256 amount) external"
        ], wallet);

        // 執行 Mint 交易
        const tx = await contract.mintTo(address, ethers.parseUnits("100", 18));

        // 更新 Firestore 中的公鑰
        if (publicKey) {
            await db.collection('users').doc(address).set({ publicKey }, { merge: true });
        }

        // 交易發送後即返回（不等待 wait 以節省 Serverless 執行時間，或者視需求等待）
        // 建議在生產環境使用 tx.wait()
        await tx.wait();

        return res.status(200).json({
            success: true,
            txHash: tx.hash,
            message: "新手入金請求已送出！"
        });
    } catch (error) {
        console.error("Airdrop Error:", error);
        return res.status(500).json({ success: false, message: error.message });
    }
};
