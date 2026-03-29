package com.paychain.service;

import com.paychain.dto.PaymentRequest;
import com.paychain.dto.PaymentResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Core service for all blockchain interactions.
 *
 * Design principle: this service NEVER signs user transactions.
 * It builds calldata and returns it to the frontend for MetaMask / wallet to sign.
 * The operator key is only used for read-only eth_call and gas estimation.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    // USDC has 6 decimal places
    private static final BigDecimal USDC_DECIMALS = BigDecimal.valueOf(1_000_000L);

    @Value("${paychain.rpc-url}")
    private String rpcUrl;

    @Value("${paychain.chain-id}")
    private long chainId;

    @Value("${paychain.contract-address}")
    private String contractAddress;

    @Value("${paychain.operator-private-key}")
    private String operatorPrivateKey;

    @Value("${paychain.usdc-address}")
    private String usdcAddress;

    @Value("${paychain.poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${paychain.poll-max-attempts:30}")
    private int pollMaxAttempts;

    private Web3j web3j;
    private Credentials operatorCredentials;

    @PostConstruct
    public void init() {
        web3j = Web3j.build(new HttpService(rpcUrl));
        operatorCredentials = Credentials.create(operatorPrivateKey);
        log.info("PaymentService initialised — chain={} contract={}", chainId, contractAddress);
    }

    // ─── initiatePayment ─────────────────────────────────────────────────────

    /**
     * Builds calldata for both the ERC-20 approve() and the contract pay() calls.
     * Returns these to the frontend; the user's wallet signs and submits them.
     */
    public PaymentResponse initiatePayment(PaymentRequest req) {
        String token = req.tokenAddress() != null ? req.tokenAddress() : usdcAddress;

        // Convert human amount to 6-decimal units
        BigInteger rawAmount = req.amount()
            .multiply(USDC_DECIMALS)
            .toBigIntegerExact();

        log.debug("Initiating payment: invoiceId={} amount={} rawAmount={} token={}",
            req.invoiceId(), req.amount(), rawAmount, token);

        // ── approve(contractAddress, amount) on the ERC-20 token ──────────────
        Function approveFunc = new Function(
            "approve",
            Arrays.asList(
                new Address(contractAddress),
                new Uint256(rawAmount)
            ),
            List.of()
        );
        String approveCalldata = FunctionEncoder.encode(approveFunc);

        // ── pay(invoiceId, merchant, token, amount) on StablecoinPayment ──────
        Function payFunc = new Function(
            "pay",
            Arrays.asList(
                new Utf8String(req.invoiceId()),
                new Address(req.merchantAddress()),
                new Address(token),
                new Uint256(rawAmount)
            ),
            List.of()
        );
        String payCalldata = FunctionEncoder.encode(payFunc);

        // ── Gas estimation (best-effort; wallet will re-estimate before sign) ─
        String estimatedGas = estimateGas(req.payerAddress(), contractAddress, payCalldata);

        return PaymentResponse.builder()
            .success(true)
            .message("Calldata ready — submit approve tx first, then pay tx")
            .contractAddress(contractAddress)
            .tokenAddress(token)
            .approveCalldata(approveCalldata)
            .calldata(payCalldata)
            .estimatedGas(estimatedGas)
            .invoiceId(req.invoiceId())
            .payer(req.payerAddress())
            .merchant(req.merchantAddress())
            .grossAmount(req.amount())
            .build();
    }

    // ─── verifyTransaction ───────────────────────────────────────────────────

    /**
     * Polls for a transaction receipt and parses the PaymentMade event.
     */
    public PaymentResponse verifyTransaction(String txHash) {
        log.debug("Verifying transaction: {}", txHash);
        try {
            Optional<TransactionReceipt> receiptOpt = pollForReceipt(txHash);

            if (receiptOpt.isEmpty()) {
                return PaymentResponse.builder()
                    .success(true)
                    .status("PENDING")
                    .txHash(txHash)
                    .message("Transaction is pending")
                    .build();
            }

            TransactionReceipt receipt = receiptOpt.get();
            boolean success = "0x1".equals(receipt.getStatus());

            if (!success) {
                return PaymentResponse.builder()
                    .success(false)
                    .status("FAILED")
                    .txHash(txHash)
                    .blockNumber(receipt.getBlockNumber().longValue())
                    .message("Transaction reverted on-chain")
                    .build();
            }

            // Parse PaymentMade(bytes32,string,address,address,address,uint256,uint256,uint256,uint256)
            PaymentMadeEvent event = parsePaymentMadeEvent(receipt);

            PaymentResponse.Builder builder = PaymentResponse.builder()
                .success(true)
                .status("CONFIRMED")
                .txHash(txHash)
                .blockNumber(receipt.getBlockNumber().longValue());

            if (event != null) {
                builder
                    .paymentId(event.paymentId)
                    .invoiceId(event.invoiceId)
                    .payer(event.payer)
                    .merchant(event.merchant)
                    .tokenAddress(event.token)
                    .grossAmount(toHumanUsdc(event.grossAmount))
                    .feeAmount(toHumanUsdc(event.feeAmount))
                    .netAmount(toHumanUsdc(event.netAmount))
                    .timestamp(Instant.ofEpochSecond(event.timestamp.longValue()));
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Error verifying transaction {}: {}", txHash, e.getMessage(), e);
            return PaymentResponse.builder()
                .success(false)
                .status("ERROR")
                .txHash(txHash)
                .error(e.getMessage())
                .build();
        }
    }

    // ─── getPaymentDetails ───────────────────────────────────────────────────

    /**
     * Reads a payment struct from the contract via eth_call.
     */
    public PaymentResponse getPaymentDetails(String paymentIdHex) {
        log.debug("Fetching payment details: {}", paymentIdHex);
        try {
            // getPayment(bytes32) returns (Payment)
            // Payment struct fields in ABI order:
            //   bytes32 paymentId, string invoiceId, address payer, address merchant,
            //   address token, uint256 grossAmount, uint256 feeAmount, uint256 netAmount,
            //   uint256 timestamp, bool refunded
            Function func = new Function(
                "getPayment",
                List.of(new Bytes32(Numeric.hexStringToByteArray(paymentIdHex))),
                Arrays.asList(
                    new TypeReference<Bytes32>() {},
                    new TypeReference<Utf8String>() {},
                    new TypeReference<Address>() {},
                    new TypeReference<Address>() {},
                    new TypeReference<Address>() {},
                    new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {},
                    new TypeReference<Bool>() {}
                )
            );

            String encodedCall = FunctionEncoder.encode(func);
            EthCall ethCall = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    operatorCredentials.getAddress(),
                    contractAddress,
                    encodedCall
                ),
                DefaultBlockParameterName.LATEST
            ).send();

            if (ethCall.hasError()) {
                return PaymentResponse.builder()
                    .success(false)
                    .error(ethCall.getError().getMessage())
                    .build();
            }

            List<Type> results = FunctionReturnDecoder.decode(
                ethCall.getValue(), func.getOutputParameters()
            );

            if (results.isEmpty()) {
                return PaymentResponse.builder()
                    .success(false)
                    .message("Payment not found")
                    .build();
            }

            BigInteger timestamp = ((Uint256) results.get(8)).getValue();
            if (timestamp.equals(BigInteger.ZERO)) {
                return PaymentResponse.builder()
                    .success(false)
                    .message("Payment not found")
                    .build();
            }

            return PaymentResponse.builder()
                .success(true)
                .paymentId(Numeric.toHexString(((Bytes32) results.get(0)).getValue()))
                .invoiceId(((Utf8String) results.get(1)).getValue())
                .payer(((Address) results.get(2)).getValue())
                .merchant(((Address) results.get(3)).getValue())
                .tokenAddress(((Address) results.get(4)).getValue())
                .grossAmount(toHumanUsdc(((Uint256) results.get(5)).getValue()))
                .feeAmount(toHumanUsdc(((Uint256) results.get(6)).getValue()))
                .netAmount(toHumanUsdc(((Uint256) results.get(7)).getValue()))
                .timestamp(Instant.ofEpochSecond(timestamp.longValue()))
                .refunded(((Bool) results.get(9)).getValue())
                .build();

        } catch (Exception e) {
            log.error("Error fetching payment {}: {}", paymentIdHex, e.getMessage(), e);
            return PaymentResponse.builder()
                .success(false)
                .error(e.getMessage())
                .build();
        }
    }

    // ─── getNetworkStatus ────────────────────────────────────────────────────

    public PaymentResponse getNetworkStatus() {
        try {
            EthBlockNumber blockNum = web3j.ethBlockNumber().send();
            EthChainId chainIdResp  = web3j.ethChainId().send();

            return PaymentResponse.builder()
                .success(true)
                .chainId(chainIdResp.getChainId().longValue())
                .latestBlock(blockNum.getBlockNumber().longValue())
                .contractAddress(contractAddress)
                .tokenAddress(usdcAddress)
                .status("ONLINE")
                .build();

        } catch (Exception e) {
            log.error("Network status error: {}", e.getMessage(), e);
            return PaymentResponse.builder()
                .success(false)
                .status("OFFLINE")
                .error(e.getMessage())
                .build();
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Optional<TransactionReceipt> pollForReceipt(String txHash) throws Exception {
        for (int i = 0; i < pollMaxAttempts; i++) {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            if (resp.getTransactionReceipt().isPresent()) {
                return resp.getTransactionReceipt();
            }
            Thread.sleep(pollIntervalMs);
        }
        return Optional.empty();
    }

    private String estimateGas(String from, String to, String data) {
        try {
            EthEstimateGas resp = web3j.ethEstimateGas(
                Transaction.createEthCallTransaction(from, to, data)
            ).send();
            if (!resp.hasError()) {
                // Add 20% buffer
                BigInteger gas = resp.getAmountUsed()
                    .multiply(BigInteger.valueOf(120))
                    .divide(BigInteger.valueOf(100));
                return gas.toString();
            }
        } catch (Exception e) {
            log.warn("Gas estimation failed: {}", e.getMessage());
        }
        return "200000"; // safe fallback
    }

    /**
     * Converts raw USDC amount (6 decimals) to a 2-decimal-place BigDecimal.
     */
    private BigDecimal toHumanUsdc(BigInteger raw) {
        return new BigDecimal(raw).divide(USDC_DECIMALS, 6, java.math.RoundingMode.DOWN)
            .stripTrailingZeros();
    }

    // ─── Event parsing ────────────────────────────────────────────────────────

    /**
     * PaymentMade event signature (keccak256):
     * PaymentMade(bytes32,string,address,address,address,uint256,uint256,uint256,uint256)
     */
    private static final String PAYMENT_MADE_TOPIC =
        "0x" + org.web3j.crypto.Hash.sha3String(
            "PaymentMade(bytes32,string,address,address,address,uint256,uint256,uint256,uint256)"
        );

    private PaymentMadeEvent parsePaymentMadeEvent(TransactionReceipt receipt) {
        return receipt.getLogs().stream()
            .filter(l -> !l.getTopics().isEmpty() && PAYMENT_MADE_TOPIC.equalsIgnoreCase(l.getTopics().get(0)))
            .findFirst()
            .map(this::decodePaymentMadeLog)
            .orElse(null);
    }

    private PaymentMadeEvent decodePaymentMadeLog(org.web3j.protocol.core.methods.response.Log logEntry) {
        try {
            // Indexed: paymentId (topic[1]), payer (topic[2]), merchant (topic[3])
            String paymentId = logEntry.getTopics().get(1);
            String payer     = "0x" + logEntry.getTopics().get(2).substring(26);
            String merchant  = "0x" + logEntry.getTopics().get(3).substring(26);

            // Non-indexed: invoiceId, token, grossAmount, feeAmount, netAmount, timestamp
            List<TypeReference<Type>> outputParams = Arrays.asList(
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Utf8String>() {},
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Address>()    {},
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Uint256>()   {},
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Uint256>()   {},
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Uint256>()   {},
                (TypeReference<Type>)(TypeReference<?>) new TypeReference<Uint256>()   {}
            );

            List<Type> decoded = FunctionReturnDecoder.decode(logEntry.getData(), outputParams);

            PaymentMadeEvent event = new PaymentMadeEvent();
            event.paymentId   = paymentId;
            event.invoiceId   = ((Utf8String) decoded.get(0)).getValue();
            event.payer       = payer;
            event.merchant    = merchant;
            event.token       = ((Address) decoded.get(1)).getValue();
            event.grossAmount = ((Uint256) decoded.get(2)).getValue();
            event.feeAmount   = ((Uint256) decoded.get(3)).getValue();
            event.netAmount   = ((Uint256) decoded.get(4)).getValue();
            event.timestamp   = ((Uint256) decoded.get(5)).getValue();
            return event;
        } catch (Exception e) {
            log.warn("Failed to parse PaymentMade log: {}", e.getMessage());
            return null;
        }
    }

    private static class PaymentMadeEvent {
        String     paymentId;
        String     invoiceId;
        String     payer;
        String     merchant;
        String     token;
        BigInteger grossAmount;
        BigInteger feeAmount;
        BigInteger netAmount;
        BigInteger timestamp;
    }
}
