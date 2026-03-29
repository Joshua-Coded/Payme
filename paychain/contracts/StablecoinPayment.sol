// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

interface IERC20 {
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function transfer(address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
    function allowance(address owner, address spender) external view returns (uint256);
}

contract StablecoinPayment {

    // ─── Structs ──────────────────────────────────────────────────────────────

    struct Payment {
        bytes32  paymentId;
        string   invoiceId;
        address  payer;
        address  merchant;
        address  token;
        uint256  grossAmount;   // full amount sent by payer
        uint256  feeAmount;     // platform fee deducted
        uint256  netAmount;     // amount received by merchant
        uint256  timestamp;
        bool     refunded;
    }

    // ─── State ────────────────────────────────────────────────────────────────

    address public owner;
    address public feeRecipient;
    uint256 public feeBasisPoints;          // e.g. 50 = 0.5%

    mapping(address => bool)    public acceptedTokens;
    mapping(bytes32 => Payment) public payments;
    mapping(address => bytes32[]) private merchantPayments;

    // ─── Events ───────────────────────────────────────────────────────────────

    event PaymentMade(
        bytes32 indexed paymentId,
        string          invoiceId,
        address indexed payer,
        address indexed merchant,
        address         token,
        uint256         grossAmount,
        uint256         feeAmount,
        uint256         netAmount,
        uint256         timestamp
    );

    event PaymentRefunded(
        bytes32 indexed paymentId,
        address indexed merchant,
        address indexed payer,
        uint256         amount,
        uint256         timestamp
    );

    event TokenWhitelisted(address indexed token, bool accepted);
    event FeeUpdated(uint256 oldFee, uint256 newFee);
    event FeeRecipientUpdated(address oldRecipient, address newRecipient);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    // ─── Modifiers ────────────────────────────────────────────────────────────

    modifier onlyOwner() {
        require(msg.sender == owner, "StablecoinPayment: caller is not the owner");
        _;
    }

    modifier onlyMerchant(bytes32 paymentId) {
        require(
            payments[paymentId].merchant == msg.sender,
            "StablecoinPayment: caller is not the merchant"
        );
        _;
    }

    // ─── Constructor ──────────────────────────────────────────────────────────

    constructor(
        address _feeRecipient,
        uint256 _feeBasisPoints,
        address[] memory _initialTokens
    ) {
        require(_feeRecipient != address(0), "StablecoinPayment: zero fee recipient");
        require(_feeBasisPoints <= 1000, "StablecoinPayment: fee exceeds 10%");

        owner         = msg.sender;
        feeRecipient  = _feeRecipient;
        feeBasisPoints = _feeBasisPoints;

        for (uint256 i = 0; i < _initialTokens.length; i++) {
            acceptedTokens[_initialTokens[i]] = true;
            emit TokenWhitelisted(_initialTokens[i], true);
        }
    }

    // ─── Core Payment Logic ───────────────────────────────────────────────────

    /**
     * @notice Pay a merchant for an invoice.
     * @param invoiceId  Human-readable invoice reference.
     * @param merchant   Merchant wallet address.
     * @param token      ERC-20 token address (must be whitelisted).
     * @param amount     Gross payment amount (token native decimals, e.g. 6 for USDC).
     *
     * Payer must call token.approve(contractAddress, amount) before this.
     */
    function pay(
        string  calldata invoiceId,
        address merchant,
        address token,
        uint256 amount
    ) external returns (bytes32 paymentId) {
        require(acceptedTokens[token], "StablecoinPayment: token not accepted");
        require(merchant != address(0),  "StablecoinPayment: zero merchant");
        require(amount > 0,              "StablecoinPayment: zero amount");
        require(bytes(invoiceId).length > 0, "StablecoinPayment: empty invoiceId");

        // Deterministic, collision-resistant payment ID
        paymentId = keccak256(abi.encodePacked(
            invoiceId,
            msg.sender,
            merchant,
            token,
            amount,
            block.timestamp,
            block.number
        ));
        require(payments[paymentId].timestamp == 0, "StablecoinPayment: duplicate paymentId");

        // Fee calculation (rounds down in favour of merchant)
        uint256 feeAmount = (amount * feeBasisPoints) / 10_000;
        uint256 netAmount = amount - feeAmount;

        // Pull funds from payer
        bool ok = IERC20(token).transferFrom(msg.sender, address(this), amount);
        require(ok, "StablecoinPayment: transferFrom failed");

        // Distribute
        if (feeAmount > 0) {
            require(IERC20(token).transfer(feeRecipient, feeAmount), "StablecoinPayment: fee transfer failed");
        }
        require(IERC20(token).transfer(merchant, netAmount), "StablecoinPayment: merchant transfer failed");

        // Persist
        payments[paymentId] = Payment({
            paymentId:   paymentId,
            invoiceId:   invoiceId,
            payer:       msg.sender,
            merchant:    merchant,
            token:       token,
            grossAmount: amount,
            feeAmount:   feeAmount,
            netAmount:   netAmount,
            timestamp:   block.timestamp,
            refunded:    false
        });
        merchantPayments[merchant].push(paymentId);

        emit PaymentMade(
            paymentId,
            invoiceId,
            msg.sender,
            merchant,
            token,
            amount,
            feeAmount,
            netAmount,
            block.timestamp
        );
    }

    /**
     * @notice Merchant-initiated refund — merchant must hold the net amount.
     * @param paymentId  The ID of the payment to refund.
     */
    function refund(bytes32 paymentId) external onlyMerchant(paymentId) {
        Payment storage p = payments[paymentId];
        require(!p.refunded,  "StablecoinPayment: already refunded");
        require(p.timestamp > 0, "StablecoinPayment: payment not found");

        p.refunded = true;

        // Merchant returns net amount to payer
        bool ok = IERC20(p.token).transferFrom(msg.sender, p.payer, p.netAmount);
        require(ok, "StablecoinPayment: refund transferFrom failed");

        emit PaymentRefunded(paymentId, p.merchant, p.payer, p.netAmount, block.timestamp);
    }

    // ─── Read Functions ───────────────────────────────────────────────────────

    function getPayment(bytes32 paymentId) external view returns (Payment memory) {
        return payments[paymentId];
    }

    function getMerchantPayments(address merchant) external view returns (bytes32[] memory) {
        return merchantPayments[merchant];
    }

    function getMerchantPaymentCount(address merchant) external view returns (uint256) {
        return merchantPayments[merchant].length;
    }

    // ─── Owner Admin ──────────────────────────────────────────────────────────

    function setTokenAccepted(address token, bool accepted) external onlyOwner {
        acceptedTokens[token] = accepted;
        emit TokenWhitelisted(token, accepted);
    }

    function setFeeBasisPoints(uint256 _feeBasisPoints) external onlyOwner {
        require(_feeBasisPoints <= 1000, "StablecoinPayment: fee exceeds 10%");
        emit FeeUpdated(feeBasisPoints, _feeBasisPoints);
        feeBasisPoints = _feeBasisPoints;
    }

    function setFeeRecipient(address _feeRecipient) external onlyOwner {
        require(_feeRecipient != address(0), "StablecoinPayment: zero address");
        emit FeeRecipientUpdated(feeRecipient, _feeRecipient);
        feeRecipient = _feeRecipient;
    }

    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "StablecoinPayment: zero address");
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }
}
