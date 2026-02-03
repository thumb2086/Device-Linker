const hre = require("hardhat");

async function main() {
  const [deployer] = await hre.ethers.getSigners();
  console.log("正在使用帳戶部署合約:", deployer.address);

  // 部署 DLinkerToken
  const DLinkerToken = await hre.ethers.getContractFactory("DLinkerToken");

  // 將部署者設為 initialOwner (管理員)
  const token = await DLinkerToken.deploy(deployer.address);

  await token.waitForDeployment();

  console.log("DLinkerToken 已成功部署到:", await token.getAddress());
  console.log("請將此地址更新到 Android App 與 Firebase Cloud Functions 的配置中。");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
