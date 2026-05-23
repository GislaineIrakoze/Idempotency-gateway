package com.igirepay.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AcceptanceTest {
    private static final String BASE_URL = System.getProperty("BASE_URL", "http://127.0.0.1:8080");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        AcceptanceTest test = new AcceptanceTest();
        test.runAll();
    }

    private void runAll() throws Exception {
        testHealthCheck();
        testHappyPath();
        testDuplicateReplay();
        testDifferentBodySameKeyConflict();
        testMissingIdempotencyKey();
        testInvalidBody();
        testInFlightDuplicateWaitsForOriginal();

        if (!failures.isEmpty()) {
            System.err.println("Acceptance tests failed:");
            for (String failure : failures) {
                System.err.println("- " + failure);
            }
            System.exit(1);
        }

        System.out.println("All acceptance tests passed.");
    }

    private void testHealthCheck() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals("health status", 200, response.statusCode());
        assertEquals("health body", "{\"status\":\"ok\"}", response.body());
    }

    private void testHappyPath() throws Exception {
        String key = randomKey("happy");
        long startedAt = System.nanoTime();
        HttpResponse<String> response = postPayment(key, "{\"amount\": 100, \"currency\": \"RWF\"}");
        long durationMillis = elapsedMillis(startedAt);

        assertEquals("happy path status", 201, response.statusCode());
        assertEquals("happy path body", "{\"message\":\"Charged 100 RWF\"}", response.body());
        assertTrue("happy path simulates processing delay", durationMillis >= 1_800);
        assertHeaderMissing("happy path has no cache hit header", response, "X-Cache-Hit");
    }

    private void testDuplicateReplay() throws Exception {
        String key = randomKey("duplicate");
        HttpResponse<String> first = postPayment(key, "{\"amount\": 100, \"currency\": \"RWF\"}");

        long startedAt = System.nanoTime();
        HttpResponse<String> second = postPayment(key, "{ \"amount\" : 100, \"currency\" : \"RWF\" }");
        long durationMillis = elapsedMillis(startedAt);

        assertEquals("duplicate first status", 201, first.statusCode());
        assertEquals("duplicate replay status matches", first.statusCode(), second.statusCode());
        assertEquals("duplicate replay body matches", first.body(), second.body());
        assertHeaderEquals("duplicate replay cache header", second, "X-Cache-Hit", "true");
        assertTrue("duplicate replay skips processing delay", durationMillis < 1_000);
    }

    private void testDifferentBodySameKeyConflict() throws Exception {
        String key = randomKey("conflict");
        postPayment(key, "{\"amount\": 100, \"currency\": \"RWF\"}");

        HttpResponse<String> response = postPayment(key, "{\"amount\": 500, \"currency\": \"RWF\"}");

        assertEquals("conflict status", 409, response.statusCode());
        assertEquals("conflict body",
                "{\"error\":\"Idempotency key already used for a different request body.\"}",
                response.body());
    }

    private void testMissingIdempotencyKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/process-payment"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\": 100, \"currency\": \"RWF\"}"))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals("missing key status", 400, response.statusCode());
        assertEquals("missing key body", "{\"error\":\"Idempotency-Key header is required.\"}", response.body());
    }

    private void testInvalidBody() throws Exception {
        HttpResponse<String> response = postPayment(randomKey("invalid"), "{\"amount\": \"oops\"}");

        assertEquals("invalid body status", 400, response.statusCode());
        assertEquals("invalid body message",
                "{\"error\":\"Request body must include numeric amount and string currency.\"}",
                response.body());
    }

    private void testInFlightDuplicateWaitsForOriginal() throws Exception {
        String key = randomKey("inflight");
        String body = "{\"amount\": 250, \"currency\": \"RWF\"}";

        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<String>> first = sendPaymentAsync(key, body);
        Thread.sleep(200);
        CompletableFuture<HttpResponse<String>> second = sendPaymentAsync(key, body);

        HttpResponse<String> firstResponse = first.join();
        HttpResponse<String> secondResponse = second.join();
        long durationMillis = elapsedMillis(startedAt);

        assertEquals("in-flight first status", 201, firstResponse.statusCode());
        assertEquals("in-flight second status", 201, secondResponse.statusCode());
        assertEquals("in-flight replay body", firstResponse.body(), secondResponse.body());
        assertHeaderEquals("in-flight replay cache header", secondResponse, "X-Cache-Hit", "true");
        assertTrue("in-flight duplicate waited for original", durationMillis >= 1_800);
        assertTrue("in-flight duplicate did not run a second 2s process", durationMillis < 3_500);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postPayment(String key, String body) throws IOException, InterruptedException {
        return sendPaymentAsync(key, body).join();
    }

    private CompletableFuture<HttpResponse<String>> sendPaymentAsync(String key, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/process-payment"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertEquals(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures.add(label + " expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private void assertTrue(String label, boolean condition) {
        if (!condition) {
            failures.add(label);
        }
    }

    private void assertHeaderEquals(String label, HttpResponse<String> response, String headerName, String expected) {
        Optional<String> value = response.headers().firstValue(headerName);
        if (value.isEmpty() || !expected.equals(value.get())) {
            failures.add(label + " expected <" + expected + "> but got <" + value.orElse("<missing>") + ">");
        }
    }

    private void assertHeaderMissing(String label, HttpResponse<String> response, String headerName) {
        if (response.headers().firstValue(headerName).isPresent()) {
            failures.add(label + " expected missing header <" + headerName + ">");
        }
    }

    private static String randomKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}

