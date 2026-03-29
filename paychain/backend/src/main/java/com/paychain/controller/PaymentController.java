package com.paychain.controller;

import com.paychain.dto.PaymentRequest;
import com.paychain.dto.PaymentResponse;
import com.paychain.service.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing PayChain payment endpoints.
 *
 * All endpoints return PaymentResponse; callers should check the `success` field.
 */
@RestController
@RequestMapping("/api/v1/payments")
@CrossOrigin(origins = "*")   // allow the frontend demo to call locally
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // ─── POST /api/v1/payments/initiate ──────────────────────────────────────

    /**
     * Build approve + pay calldata for the frontend wallet to sign.
     *
     * Request body: { invoiceId, payerAddress, merchantAddress, amount, tokenAddress? }
     * Response:     { calldata, approveCalldata, estimatedGas, contractAddress, ... }
     */
    @PostMapping("/initiate")
    public ResponseEntity<PaymentResponse> initiatePayment(
        @Valid @RequestBody PaymentRequest request
    ) {
        log.info("POST /initiate — invoice={} payer={} merchant={} amount={}",
            request.invoiceId(), request.payerAddress(), request.merchantAddress(), request.amount());

        PaymentResponse response = paymentService.initiatePayment(request);
        return ResponseEntity.ok(response);
    }

    // ─── GET /api/v1/payments/verify/{txHash} ────────────────────────────────

    /**
     * Poll for a transaction receipt and decode the PaymentMade event.
     *
     * @param txHash  Ethereum transaction hash (0x-prefixed).
     */
    @GetMapping("/verify/{txHash}")
    public ResponseEntity<PaymentResponse> verifyTransaction(
        @PathVariable String txHash
    ) {
        log.info("GET /verify/{}", txHash);

        if (!txHash.matches("^0x[0-9a-fA-F]{64}$")) {
            return ResponseEntity.badRequest()
                .body(PaymentResponse.error("Invalid txHash format — expected 0x + 64 hex chars"));
        }

        PaymentResponse response = paymentService.verifyTransaction(txHash);
        return ResponseEntity.ok(response);
    }

    // ─── GET /api/v1/payments/{paymentId} ────────────────────────────────────

    /**
     * Read a payment struct from the contract (eth_call, no gas).
     *
     * @param paymentId  bytes32 payment ID as 0x-prefixed hex.
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
        @PathVariable String paymentId
    ) {
        log.info("GET /{}", paymentId);

        if (!paymentId.matches("^0x[0-9a-fA-F]{64}$")) {
            return ResponseEntity.badRequest()
                .body(PaymentResponse.error("Invalid paymentId format — expected 0x + 64 hex chars"));
        }

        PaymentResponse response = paymentService.getPaymentDetails(paymentId);
        return ResponseEntity.ok(response);
    }

    // ─── GET /api/v1/payments/network/status ─────────────────────────────────

    /**
     * Returns chain ID, latest block number, and contract address.
     * Use this to confirm the backend is connected to the right network.
     */
    @GetMapping("/network/status")
    public ResponseEntity<PaymentResponse> networkStatus() {
        log.debug("GET /network/status");
        PaymentResponse response = paymentService.getNetworkStatus();
        return ResponseEntity.ok(response);
    }
}
