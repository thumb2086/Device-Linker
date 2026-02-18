const { ethers } = require("ethers");

const CONTRACT_ADDRESS = "0x531aa0c02ee61bfdaf2077356293f2550a969142";
const RPC_URL = "https://sepolia.base.org";

module.exports = async (req, res) => {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Method Not Allowed' });
    }

    const { address } = req.body;

    if (!address || !ethers.isAddress(address)) {
        return res.status(400).json({ success: false, message: '無效的錢包地址' });
    }

    try {
        const provider = new ethers.JsonRpcProvider(RPC_URL);
        const contract = new ethers.Contract(CONTRACT_ADDRESS, [
            "function balanceOf(address account) external view returns (uint256)"
        ], provider);

        // 直接從區塊鏈查詢餘額
        const balanceWei = await contract.balanceOf(address);
        const balance = ethers.formatUnits(balanceWei, 18);

        return res.status(200).json({
            success: true,
            balance: balance,
            source: "blockchain"
        });
    } catch (error) {
        console.error("Get Balance Error:", error);
        return res.status(500).json({ success: false, message: error.message });
    }
};
