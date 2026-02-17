const { ethers } = require("ethers");
const admin = require("firebase-admin");

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
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Method Not Allowed' });
    }

    const { from, to, amount, signature } = req.body;

    if (!from || !to || !amount) {
        return res.status(400).json({ success: false, message: '缺少必要參數' });
    }

    const adminPrivateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!adminPrivateKey) {
        return res.status(500).json({ success: false, message: '伺服器私鑰未配置' });
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(adminPrivateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function transferFrom(address from, address to, uint256 amount) external"
        ], wallet);

        // 注意：在區塊鏈上執行 transferFrom 通常需要授權 (Approve)
        // 這裡假設後端有權限或是特定的合約邏輯
        // 或者這只是一個範例，實際邏輯可能不同
        const tx = await contract.transferFrom(from, to, ethers.parseUnits(amount, 18));
        await tx.wait();

        return res.status(200).json({
            success: true,
            txHash: tx.hash,
            message: "轉帳成功！"
        });
    } catch (error) {
        console.error("Transfer Error:", error);
        return res.status(500).json({ success: false, message: error.message });
    }
};
