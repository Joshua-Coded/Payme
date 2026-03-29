package com.paychain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Generic API response wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(

    boolean success,
    String  message,

    // ── Initiate ──────────────────────────────────────────────────────────────
    String  contractAddress,
    String  calldata,           // ABI-encoded pay() call — wallet signs this
    String  approveCalldata,    // ABI-encoded approve() call for the ERC-20 token
    String  tokenAddress,
    String  estimatedGas,

    // ── Payment details ───────────────────────────────────────────────────────
    String     paymentId,
    String     invoiceId,
    String     payer,
    String     merchant,
    BigDecimal grossAmount,     // human-readable (USDC units, 6 dec → 2 dec display)
    BigDecimal feeAmount,
    BigDecimal netAmount,
    Instant    timestamp,
    boolean    refunded,

    // ── Network status ────────────────────────────────────────────────────────
    String  txHash,
    String  status,             // "PENDING" | "CONFIRMED" | "FAILED" | "NOT_FOUND"
    Long    blockNumber,
    Long    chainId,
    Long    latestBlock,
    String  error

) {
    // ── Factory helpers ───────────────────────────────────────────────────────

    public static PaymentResponse error(String message) {
        return new PaymentResponse(
            false, message,
            null, null, null, null, null,
            null, null, null, null,
            null, null, null, null, false,
            null, null, null, null, null, message
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    // Fluent builder for the large record
    public static final class Builder {
        private boolean    success      = true;
        private String     message;
        private String     contractAddress;
        private String     calldata;
        private String     approveCalldata;
        private String     tokenAddress;
        private String     estimatedGas;
        private String     paymentId;
        private String     invoiceId;
        private String     payer;
        private String     merchant;
        private BigDecimal grossAmount;
        private BigDecimal feeAmount;
        private BigDecimal netAmount;
        private Instant    timestamp;
        private boolean    refunded;
        private String     txHash;
        private String     status;
        private Long       blockNumber;
        private Long       chainId;
        private Long       latestBlock;
        private String     error;

        public Builder success(boolean v)           { this.success = v; return this; }
        public Builder message(String v)            { this.message = v; return this; }
        public Builder contractAddress(String v)    { this.contractAddress = v; return this; }
        public Builder calldata(String v)           { this.calldata = v; return this; }
        public Builder approveCalldata(String v)    { this.approveCalldata = v; return this; }
        public Builder tokenAddress(String v)       { this.tokenAddress = v; return this; }
        public Builder estimatedGas(String v)       { this.estimatedGas = v; return this; }
        public Builder paymentId(String v)          { this.paymentId = v; return this; }
        public Builder invoiceId(String v)          { this.invoiceId = v; return this; }
        public Builder payer(String v)              { this.payer = v; return this; }
        public Builder merchant(String v)           { this.merchant = v; return this; }
        public Builder grossAmount(BigDecimal v)    { this.grossAmount = v; return this; }
        public Builder feeAmount(BigDecimal v)      { this.feeAmount = v; return this; }
        public Builder netAmount(BigDecimal v)      { this.netAmount = v; return this; }
        public Builder timestamp(Instant v)         { this.timestamp = v; return this; }
        public Builder refunded(boolean v)          { this.refunded = v; return this; }
        public Builder txHash(String v)             { this.txHash = v; return this; }
        public Builder status(String v)             { this.status = v; return this; }
        public Builder blockNumber(Long v)          { this.blockNumber = v; return this; }
        public Builder chainId(Long v)              { this.chainId = v; return this; }
        public Builder latestBlock(Long v)          { this.latestBlock = v; return this; }
        public Builder error(String v)              { this.error = v; return this; }

        public PaymentResponse build() {
            return new PaymentResponse(
                success, message,
                contractAddress, calldata, approveCalldata, tokenAddress, estimatedGas,
                paymentId, invoiceId, payer, merchant,
                grossAmount, feeAmount, netAmount, timestamp, refunded,
                txHash, status, blockNumber, chainId, latestBlock, error
            );
        }
    }
}
