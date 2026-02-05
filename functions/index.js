require('dotenv').config(); // 載入 .env 文件

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

// 合約資訊 (由使用者提供)
const CONTRACT_ADDRESS = "0x531aa0c02ee61bfdaf2077356293f2550a969142";
const RPC_URL = "https://sepolia.base.org";

// 簡化版的 ABI，只需包含我們需要的函數
const ABI = [
    "function mintTo(address to, uint256 amount) external",
    "function transfer(address to, uint256 amount) external", // Assuming owner calls transfer or we use a relay
    "function transferFrom(address from, address to, uint256 amount) external",
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
    const publicKey = data.publicKey; // Base64 encoded

    if (!ethers.isAddress(targetAddress)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址');
    }

    // 儲存公鑰
    if (publicKey) {
        await db.collection('users').doc(targetAddress).set({
            publicKey: publicKey
        }, { merge: true });
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
 * transfer: 轉帳功能 (Relay)
 */
exports.transfer = functions.runWith({ secrets: ["ADMIN_PRIVATE_KEY"] }).https.onCall(async (data, context) => {
    const { from, to, amount, signature } = data;

    if (!ethers.isAddress(from) || !ethers.isAddress(to)) {
        throw new functions.https.HttpsError('invalid-argument', '無效的地址');
    }

    // 獲取使用者儲存的公鑰
    const userDoc = await db.collection('users').doc(from).get();
    if (!userDoc.exists || !userDoc.data().publicKey) {
        throw new functions.https.HttpsError('failed-precondition', '使用者未註冊公鑰');
    }

    const storedPublicKey = userDoc.data().publicKey;

    // 驗證簽名
    // 簽名內容為 "transfer:${to}:${amount}"
    const message = `transfer:${to}:${amount}`;

    let isValid = false;
    try {
        const publicKeyBuffer = Buffer.from(storedPublicKey, 'base64');
        const signatureBuffer = Buffer.from(signature, 'base64');

        const verify = crypto.createVerify('SHA256');
        verify.update(message);
        verify.end();

        isValid = verify.verify(
            {
                key: publicKeyBuffer,
                format: 'der',
                type: 'spki',
            },
            signatureBuffer
        );
    } catch (e) {
        console.error("Signature Verification Error:", e);
        throw new functions.https.HttpsError('internal', '簽名驗證過程出錯');
    }

    if (!isValid) {
        throw new functions.https.HttpsError('permission-denied', '簽名驗證失敗');
    }

    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, ABI, wallet);

        // 由管理員代付 Gas，調用合約的轉帳功能
        // 這裡如果是自定義 Token，通常是由管理員調用 transferFrom 或特殊函數
        // 為了展示，我們假設管理員可以代表使用者轉帳 (需要合約授權或特定邏輯)
        // 或是我們改用 mint 100 給 to，扣除 from 的餘額 (中繼模式)

        const decimals = await contract.decimals();
        const parsedAmount = ethers.parseUnits(amount, decimals);

        // 執行轉帳
        // 注意：為了讓管理員能代表使用者轉帳，使用者必須先 approve 管理員，
        // 或者合約必須支援 Meta-Transaction。在此範例中我們使用 transferFrom。
        // 如果合約不支援，則可能需要合約層面的調整。
        const tx = await contract.transferFrom(from, to, parsedAmount);
        await tx.wait();

        // 更新雙方餘額
        await updateUserBalance(from);
        await updateUserBalance(to);

        return {
            success: true,
            txHash: tx.hash,
            message: "轉帳成功 (Relay)"
        };
    } catch (error) {
        console.error("Transfer Error:", error);
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
