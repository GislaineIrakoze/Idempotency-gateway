package com.igirepay.gateway;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PaymentRequest {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\"amount\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\"currency\"\\s*:\\s*\"([A-Za-z]{3})\"");

    private final BigDecimal amount;
    private final String currency;

    private PaymentRequest(BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }

        this.amount = amount;
        this.currency = currency.toUpperCase(Locale.ROOT);
    }

    static PaymentRequest fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Body is empty.");
        }

        Matcher amountMatcher = AMOUNT_PATTERN.matcher(json);
        Matcher currencyMatcher = CURRENCY_PATTERN.matcher(json);
        if (!amountMatcher.find() || !currencyMatcher.find()) {
            throw new IllegalArgumentException("Missing amount or currency.");
        }

        return new PaymentRequest(new BigDecimal(amountMatcher.group(1)), currencyMatcher.group(1));
    }

    String formattedAmount() {
        return amount.stripTrailingZeros().toPlainString();
    }

    String currency() {
        return currency;
    }

    String fingerprint() {
        return formattedAmount() + "|" + currency;
    }
}
