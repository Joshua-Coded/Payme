package com.paychain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Request body for POST /api/v1/payments/initiate.
 *
 * The frontend sends this; the backend returns calldata for the user's wallet
 * to sign — the server never holds the user's private key.
 */
public record PaymentRequest(

    @NotBlank(message = "invoiceId is required")
    String invoiceId,

    @NotBlank(message = "payerAddress is required")
    @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "payerAddress must be a valid Ethereum address")
    String payerAddress,

    @NotBlank(message = "merchantAddress is required")
    @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "merchantAddress must be a valid Ethereum address")
    String merchantAddress,

    /**
     * Human-readable USDC amount, e.g. "100.50".
     * The backend converts to 6-decimal units before encoding.
     */
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.000001", message = "amount must be positive")
    BigDecimal amount,

    /**
     * ERC-20 token address. Defaults to USDC if null.
     */
    @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "tokenAddress must be a valid Ethereum address")
    String tokenAddress

) {}
