const { ethers } = require("hardhat");

/**
 * Deploy StablecoinPayment to the current network.
 *
 * Environment variables (set in .env):
 *   FEE_RECIPIENT      – address that receives platform fees (defaults to deployer)
 *   FEE_BASIS_POINTS   – e.g. 50 for 0.5% (defaults to 50)
 *
 * Sepolia mock USDC: 0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238
 */
async function main() {
  const [deployer] = await ethers.getSigners();
  const network = await ethers.provider.getNetwork();

  console.log("=".repeat(60));
  console.log("PayChain Deployer");
  console.log("=".repeat(60));
  console.log(`Network   : ${network.name} (chainId=${network.chainId})`);
  console.log(`Deployer  : ${deployer.address}`);

  const balance = await ethers.provider.getBalance(deployer.address);
  console.log(`Balance   : ${ethers.formatEther(balance)} ETH`);
  console.log("-".repeat(60));

  // ── Config ────────────────────────────────────────────────────────────────
  const feeRecipient    = process.env.FEE_RECIPIENT    || deployer.address;
  const feeBasisPoints  = Number(process.env.FEE_BASIS_POINTS || 50);

  // Whitelisted tokens per network
  const tokensByChain = {
    // Sepolia
    11155111: [
      "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", // Sepolia mock USDC
    ],
    // Hardhat / localhost — deploy a mock token during test setup instead
    31337: [],
  };

  const initialTokens = tokensByChain[Number(network.chainId)] || [];

  console.log(`Fee Recipient  : ${feeRecipient}`);
  console.log(`Fee Basis Pts  : ${feeBasisPoints} (${feeBasisPoints / 100}%)`);
  console.log(`Initial Tokens : ${initialTokens.length ? initialTokens.join(", ") : "(none — add via setTokenAccepted)"}`);
  console.log("-".repeat(60));

  // ── Deploy ────────────────────────────────────────────────────────────────
  const StablecoinPayment = await ethers.getContractFactory("StablecoinPayment");
  console.log("Deploying StablecoinPayment...");

  const contract = await StablecoinPayment.deploy(
    feeRecipient,
    feeBasisPoints,
    initialTokens
  );

  await contract.waitForDeployment();
  const address = await contract.getAddress();

  console.log(`\nContract deployed at: ${address}`);
  console.log("=".repeat(60));
  console.log("\nUpdate your .env / application.yml:");
  console.log(`  CONTRACT_ADDRESS=${address}`);
  console.log(`  CHAIN_ID=${network.chainId}`);

  // Verify on Etherscan (non-local networks)
  if (Number(network.chainId) !== 31337 && process.env.ETHERSCAN_API_KEY) {
    console.log("\nWaiting 5 blocks before Etherscan verification...");
    await contract.deploymentTransaction().wait(5);
    try {
      await run("verify:verify", {
        address,
        constructorArguments: [feeRecipient, feeBasisPoints, initialTokens],
      });
      console.log("Contract verified on Etherscan.");
    } catch (e) {
      console.warn("Etherscan verification failed:", e.message);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
