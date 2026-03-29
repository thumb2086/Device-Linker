require('dotenv').config();
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { ethers } = require("ethers");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com";
const SUPPORTED_TOKENS = Object.freeze({
    zhixi: {
        id: "zhixi",
        symbol: "ZHIXI",
        name: "子熙幣",
        address: "0xe3d9af5f15857cb01e0614fa281fcc3256f62050",
    },
    youjian: {
        id: "youjian",
        symbol: "YOUJIAN",
        name: "佑戩幣",
        address: "0x82D6aDB17d58820324D86B378775350D03a071AE",
    },
});
const DEFAULT_TOKEN = SUPPORTED_TOKENS.zhixi;
const SUPPORTED_TOKENS_BY_ADDRESS = new Map(
    Object.values(SUPPORTED_TOKENS).map((token) => {
        const normalizedAddress = ethers.getAddress(token.address);
        return [normalizedAddress.toLowerCase(), { ...token, address: normalizedAddress }];
    })
);

function sendResponse(res, data) {
    res.status(200).json({ success: true, ...data });
}

function sendError(res, message, code = 400) {
    res.status(code).json({ success: false, error: message });
}

function resolveToken(tokenAddress) {
    const rawAddress = (tokenAddress || DEFAULT_TOKEN.address).trim();
    const normalizedAddress = ethers.getAddress(rawAddress.toLowerCase());
    const token = SUPPORTED_TOKENS_BY_ADDRESS.get(normalizedAddress.toLowerCase());
    if (!token) {
        throw new Error(`Unsupported token address: ${normalizedAddress}`);
    }
    return token;
}

function createTokenContract(token, abi, signerOrProvider) {
    return new ethers.Contract(token.address, abi, signerOrProvider);
}

function sanitizeToken(token) {
    return {
        id: token.id,
        symbol: token.symbol,
        name: token.name,
        address: token.address,
    };
}

function extractRawPubKeyFromSPKI(pubKeyBase64) {
    const der = Buffer.from(pubKeyBase64, 'base64');
    let pos = der.indexOf(0x03);
    if (pos === -1) throw new Error("No BIT STRING found");

    pos += 1;
    const bitLength = der[pos++];
    if (bitLength !== 0x42) {
        console.warn(`Unexpected BIT STRING length: 0x${bitLength.toString(16)}, expected 0x42`);
    }

    pos += 1;
    const rawPub = der.slice(pos, pos + 65);
    if (rawPub.length !== 65 || rawPub[0] !== 0x04) {
        throw new Error(`Extracted invalid raw pubkey: len=${rawPub.length}, prefix=0x${rawPub[0]?.toString(16)}`);
    }

    return `0x${rawPub.toString('hex')}`;
}

async function updateUserBalance(address, tokenAddress) {
    try {
        const cleanAddress = ethers.getAddress(address.toLowerCase());
        const token = resolveToken(tokenAddress);
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const contract = createTokenContract(token, [
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
            balances: {
                [token.id]: formattedBalance,
            },
            tokenBalances: {
                [token.address]: formattedBalance,
            },
            lastUpdated: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        return { balance: formattedBalance, token };
    } catch (error) {
        console.error("Balance Update Error:", error);
        return { balance: "0", token: null };
    }
}

async function mintAirdrop({ address, publicKey, tokenAddress }) {
    const privateKey = process.env.ADMIN_PRIVATE_KEY;
    if (!privateKey) throw new Error("ADMIN_PRIVATE_KEY is not configured");

    const token = resolveToken(tokenAddress);
    const provider = new ethers.JsonRpcProvider(RPC_URL);
    const wallet = new ethers.Wallet(privateKey, provider);
    const contract = createTokenContract(token, [
        "function mintTo(address to, uint256 amount) external"
    ], wallet);

    const tx = await contract.mintTo(address, ethers.parseUnits("100", 18));
    if (publicKey) {
        await db.collection('users').doc(address).set({ publicKey }, { merge: true });
    }

    await tx.wait();
    const { balance } = await updateUserBalance(address, token.address);

    return {
        txHash: tx.hash,
        balance,
        token,
    };
}

exports.requestAirdrop = functions.runWith({
    timeoutSeconds: 60,
    secrets: ["ADMIN_PRIVATE_KEY"]
}).https.onCall(async (data) => {
    let { address, publicKey, tokenAddress } = data;
    try {
        address = ethers.getAddress(address.toLowerCase());
    } catch (error) {
        throw new functions.https.HttpsError('invalid-argument', 'Invalid wallet address');
    }

    try {
        const result = await mintAirdrop({ address, publicKey, tokenAddress });
        return {
            success: true,
            txHash: result.txHash,
            balance: result.balance,
            token: sanitizeToken(result.token),
        };
    } catch (error) {
        console.error("Airdrop Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});

exports.transfer = functions.runWith({
    timeoutSeconds: 60,
    secrets: ["ADMIN_PRIVATE_KEY"]
}).https.onCall(async (data) => {
    let { from, to, publicKey, tokenAddress } = data;

    try {
        const cleanFrom = ethers.getAddress(from.toLowerCase());
        const cleanTo = ethers.getAddress(to.toLowerCase());
        const token = resolveToken(tokenAddress);

        let pubDerived = "";
        if (publicKey) {
            try {
                const rawPubHex = extractRawPubKeyFromSPKI(publicKey);
                pubDerived = ethers.computeAddress(rawPubHex).toLowerCase();
            } catch (error) {
                console.error("PubKey Extraction Failed:", error);
                throw new functions.https.HttpsError('invalid-argument', `Invalid public key: ${error.message}`);
            }
        }

        if (pubDerived !== cleanFrom.toLowerCase()) {
            console.error(`PubKey Mismatch: Derived=${pubDerived} | Expected=${cleanFrom.toLowerCase()}`);
            throw new functions.https.HttpsError('permission-denied', 'Public key does not match sender address');
        }

        return {
            success: true,
            token: sanitizeToken(token),
            debug: {
                pubDerived,
                from: cleanFrom,
                to: cleanTo,
                status: "Signature verified. On-chain transfer is not wired in this relay yet."
            }
        };
    } catch (error) {
        console.error("Transfer Error:", error);
        throw new functions.https.HttpsError('internal', error.message);
    }
});

exports.user = functions.https.onRequest(async (req, res) => {
    if (req.method !== 'POST') return sendError(res, 'Only POST methods are allowed', 405);
    const { action, sessionId, username, password, address, page, limit } = req.body;

    try {
        switch (action) {
            case 'get_status': {
                if (!sessionId) return sendError(res, 'sessionId is required');
                const sessionDoc = await db.collection('sessions').doc(sessionId).get();
                if (!sessionDoc.exists) {
                    return sendResponse(res, { status: "pending" });
                }
                const sessionData = sessionDoc.data();
                sendResponse(res, {
                    status: sessionData.status || "authorized",
                    address: sessionData.address || "0x...",
                    displayName: sessionData.displayName || "User",
                    balance: sessionData.balance || "0.00",
                    vipLevel: sessionData.vipLevel || "Standard"
                });
                break;
            }

            case 'create_session': {
                const newSessionId = crypto.randomBytes(16).toString('hex');
                await db.collection('sessions').doc(newSessionId).set({
                    status: 'pending',
                    createdAt: admin.firestore.FieldValue.serverTimestamp()
                });
                sendResponse(res, {
                    sessionId: newSessionId,
                    deepLink: `dlinker://login/${newSessionId}`
                });
                break;
            }

            case 'custody_login': {
                if (!username || !password) return sendError(res, 'Username and password are required');
                const custodySessionId = crypto.randomBytes(16).toString('hex');
                sendResponse(res, {
                    sessionId: custodySessionId,
                    address: "0xCustodyAccountAddress"
                });
                break;
            }

            case 'get_history': {
                if (!address) return sendError(res, 'address is required');
                sendResponse(res, {
                    history: [],
                    page: page || 1,
                    limit: limit || 20
                });
                break;
            }

            default:
                sendError(res, `Unknown action: ${action}`);
        }
    } catch (error) {
        sendError(res, error.message, 500);
    }
});

exports.wallet = functions.https.onRequest(async (req, res) => {
    if (req.method !== 'POST') return sendError(res, 'Only POST methods are allowed', 405);
    const {
        action,
        address,
        sessionId,
        to,
        amount,
        publicKey,
        signature,
        tokenAddress
    } = req.body;

    try {
        switch (action) {
            case 'get_balance': {
                if (!address) return sendError(res, 'address is required');
                const result = await updateUserBalance(address, tokenAddress);
                sendResponse(res, {
                    balance: result.balance,
                    token: result.token ? sanitizeToken(result.token) : null
                });
                break;
            }

            case 'secure_transfer': {
                if (!sessionId || !to || !amount || !signature) {
                    return sendError(res, 'Missing parameters');
                }
                sendResponse(res, {
                    txHash: "0xPendingTransferHash",
                    token: sanitizeToken(resolveToken(tokenAddress))
                });
                break;
            }

            case 'airdrop': {
                if (!sessionId || !address) return sendError(res, 'Missing sessionId or address');
                const result = await mintAirdrop({ address, publicKey, tokenAddress });
                sendResponse(res, {
                    reward: "100",
                    txHash: result.txHash,
                    balance: result.balance,
                    token: sanitizeToken(result.token)
                });
                break;
            }

            default:
                sendError(res, `Unknown action: ${action}`);
        }
    } catch (error) {
        sendError(res, error.message, 500);
    }
});

exports.stats = functions.https.onRequest(async (req, res) => {
    if (req.method !== 'POST') return sendError(res, 'Only POST methods are allowed', 405);
    const { action } = req.body;

    try {
        switch (action) {
            case 'total_bet':
                sendResponse(res, {
                    leaderboard: [],
                    generatedAt: new Date().toISOString()
                });
                break;

            case 'net_worth':
                sendResponse(res, {
                    leaderboard: []
                });
                break;

            default:
                sendError(res, `Unknown action: ${action}`);
        }
    } catch (error) {
        sendError(res, error.message, 500);
    }
});

exports.game = functions.https.onRequest(async (req, res) => {
    if (req.method !== 'POST') return sendError(res, 'Only POST methods are allowed', 405);
    const gameType = req.query.game;
    const { action } = req.body;

    if (!gameType) return sendError(res, 'game type is required in query');

    try {
        sendResponse(res, {
            game: gameType,
            action,
            result: "Action processed"
        });
    } catch (error) {
        sendError(res, error.message, 500);
    }
});
