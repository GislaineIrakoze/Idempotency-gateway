package com.igirepay.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class PaymentHandler implements HttpHandler {
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;

    PaymentHandler(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, ResponseSnapshot.json(405, "{\"error\":\"Method not allowed.\"}"), false);
            return;
        }

        String key = getFirstHeader(exchange.getRequestHeaders(), "Idempotency-Key");
        if (key == null || key.isBlank()) {
            send(exchange, ResponseSnapshot.json(400, "{\"error\":\"Idempotency-Key header is required.\"}"), false);
            return;
        }

        key = key.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            send(exchange, ResponseSnapshot.json(400, "{\"error\":\"Idempotency-Key must be 255 characters or fewer.\"}"), false);
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        PaymentRequest paymentRequest;
        try {
            paymentRequest = PaymentRequest.fromJson(requestBody);
        } catch (IllegalArgumentException exception) {
            send(exchange, ResponseSnapshot.json(400, "{\"error\":\"Request body must include numeric amount and string currency.\"}"), false);
            return;
        }

        IdempotencyStore.StoredResponse storedResponse = store.execute(key, paymentRequest.fingerprint(), () -> processPayment(paymentRequest));
        send(exchange, storedResponse.response(), storedResponse.cacheHit());
    }

    private ResponseSnapshot processPayment(PaymentRequest paymentRequest) {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment processing was interrupted.", exception);
        }

        String message = "Charged " + paymentRequest.formattedAmount() + " " + paymentRequest.currency();
        return ResponseSnapshot.json(201, "{\"message\":\"" + escapeJson(message) + "\"}");
    }

    private void send(HttpExchange exchange, ResponseSnapshot response, boolean cacheHit) throws IOException {
        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        if (cacheHit) {
            exchange.getResponseHeaders().set("X-Cache-Hit", "true");
        }
        exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(responseBytes);
        }
    }

    private static String getFirstHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }

        return values.get(0);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
