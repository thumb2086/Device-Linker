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

module.exports = async (req, res) => {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Method Not Allowed' });
    }

    const { address } = req.body;

    if (!address) {
        return res.status(400).json({ success: false, message: 'Missing wallet address' });
    }

    try {
        const userDoc = await db.collection('users').doc(address).get();
        if (!userDoc.exists) {
            return res.status(200).json({ success: true, balance: "0" });
        }
        const balance = userDoc.data().balance || "0";
        return res.status(200).json({ success: true, balance: balance });
    } catch (error) {
        console.error("Get Balance Error:", error);
        return res.status(500).json({ success: false, message: error.message });
    }
};
