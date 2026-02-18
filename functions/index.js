require('dotenv').config();
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const CONTRACT_ADDRESS = "0xe3d9af5f15857cb01e0614fa281fcc3256f62050";
const RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com";

// 修正版公鑰提取函式 (精準版，針對 Android KeyStore DER 格式)
function extractRawPubKeyFromSPKI(pubKeyBase64) {
    const der = Buffer.from(pubKeyBase64, 'base64');

    // 找 BIT STRING (0x03)
    let pos = der.indexOf(0x03);
    if (pos === -1) throw new Error("No BIT STRING found");

    pos += 1; // 跳過 0x03
    const bitLength = der[pos++];
    if (bitLength !== 0x42) {
        // 某些設備長度可能略有不同，容錯處理
        console.warn(`Unexpected BIT STRING length: 0x${bitLength.toString(16)}, expected 0x42`);
    }

    pos += 1; // 跳過 unused bits (0x00)

    // 現在 pos 應該指向 0x04...
    const rawPub = der.slice(pos, pos + 65);

    if (rawPub.length !== 65 || rawPub[0] !== 0x04) {
        throw new Error(`Extracted invalid raw pubkey: len=${rawPub.length}, prefix=0x${rawPub[0]?.toString(16)}`);
    }

    return '0x' + rawPub.toString('hex');
}

async function updateUserBalance(address) {
    try {
        const cleanAddress = ethers.getAddress(address.toLowerCase());
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function balanceOf(address account) external view returns (uint256)",
            "function decimals() external view returns (uint8)"
        ], provider);

        const [balance, decimals] = await Promise.all([
            contract.balanceOf(cleanAddress),
            contract.decimals()
        ]);
        const formattedBalance = ethers.formatUnits(balance, decimals);

        await db.collection('users').doc(cleanAddress).set({
            balance: formattedBalance,
            lastUpdated: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        return formattedBalance;
    } catch (e) {
        console.error("Balance Update Error:", e);
        return "0";
    }
}

exports.requestAirdrop = functions.runWith({
    timeoutSeconds: 60,
    secrets: ["ADMIN_PRIVATE_KEY"]
}).https.onCall(async (data, context) => {
    let { address, publicKey, signature } = data;
    try {
        address = ethers.getAddress(address.toLowerCase());
    } catch (e) {
        throw new functions.https.HttpsError('invalid-argument', '無效的錢包地址格式');
    }

    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!privateKey) throw new functions.https.HttpsError('internal', '伺服器私鑰未配置');

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function mintTo(address to, uint256 amount) external"
        ], wallet);

        const tx = await contract.mintTo(address, ethers.parseUnits("100", 18));
        if (publicKey) await db.collection('users').doc(address).set({ publicKey }, { merge: true });

        await tx.wait();
        const newBalance = await updateUserBalance(address);

        return { success: true, txHash: tx.hash, balance: newBalance };
    } catch (error) {
        console.error("Airdrop Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});

exports.transfer = functions.runWith({
    timeoutSeconds: 60,
    secrets: ["ADMIN_PRIVATE_KEY"]
}).https.onCall(async (data, context) => {
    let { from, to, amount, signature, publicKey } = data;

    try {
        const cleanFrom = ethers.getAddress(from.toLowerCase());
        const cleanTo = ethers.getAddress(to.toLowerCase());

        // 1. 公鑰提取與地址還原
        let pubDerived = "";
        if (publicKey) {
            try {
                const rawPubHex = extractRawPubKeyFromSPKI(publicKey);
                console.log("Raw pubkey extracted:", rawPubHex);
                pubDerived = ethers.computeAddress(rawPubHex).toLowerCase();
            } catch (e) {
                console.error("PubKey Extraction Failed:", e);
                throw new functions.https.HttpsError('invalid-argument', '公鑰提取失敗: ' + e.message);
            }
        }

        // 2. 校驗還原地址是否匹配
        if (pubDerived !== cleanFrom.toLowerCase()) {
            console.error(`PubKey Mismatch: Derived=${pubDerived} | Expected=${cleanFrom.toLowerCase()}`);
            throw new functions.https.HttpsError('permission-denied', '公鑰與發送者地址不匹配 (Bad Point?)');
        }

        // 3. 後續簽名驗證與鏈上轉帳邏輯 (待完善)
        const privateKey = process.env.ADMIN_PRIVATE_KEY;
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const wallet = new ethers.Wallet(privateKey, provider);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function transferFromDevice(address from, address to, uint256 amount, bytes signature) external returns (bool)"
        ], wallet);

        // TODO: 目前暫時拋出成功，讓您檢查 Vercel Logs 中的 pubDerived 結果
        // 實際發送交易時需要對齊簽名格式 (SHA-256 vs Keccak-256)
        return {
            success: true,
            debug: {
                pubDerived,
                from: cleanFrom,
                status: "公鑰校驗成功，進入簽名驗證階段"
            }
        };

    } catch (error) {
        console.error("Transfer Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});
