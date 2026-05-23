package com.igirepay.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class IdempotencyStore {
    private static final String BODY_MISMATCH_MESSAGE = "Idempotency key already used for a different request body.";

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ScheduledExecutorService cleanupExecutor;

    IdempotencyStore(Duration ttl) {
        this.ttl = ttl;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "idempotency-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::removeExpiredRecords, 60, 60, TimeUnit.SECONDS);
    }

    StoredResponse execute(String key, String requestFingerprint, Supplier<ResponseSnapshot> processor) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(processor, "processor");

        String requestHash = sha256(requestFingerprint);

        while (true) {
            Instant now = Instant.now();
            IdempotencyRecord existing = records.get(key);
            if (existing != null && existing.isExpired(now, ttl)) {
                records.remove(key, existing);
                continue;
            }

            if (existing == null) {
                IdempotencyRecord fresh = new IdempotencyRecord(requestHash, now);
                IdempotencyRecord raced = records.putIfAbsent(key, fresh);
                if (raced == null) {
                    return processFirstRequest(key, fresh, processor);
                }

                existing = raced;
            }

            if (!existing.requestHash().equals(requestHash)) {
                return StoredResponse.conflict(BODY_MISMATCH_MESSAGE);
            }

            return waitForReplay(existing.result());
        }
    }

    int size() {
        return records.size();
    }

    private StoredResponse processFirstRequest(String key, IdempotencyRecord record, Supplier<ResponseSnapshot> processor) {
        try {
            ResponseSnapshot response = processor.get();
            record.result().complete(response);
            return StoredResponse.original(response);
        } catch (RuntimeException exception) {
            record.result().completeExceptionally(exception);
            records.remove(key, record);
            throw exception;
        }
    }

    private StoredResponse waitForReplay(CompletableFuture<ResponseSnapshot> result) {
        try {
            return StoredResponse.replayed(result.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return StoredResponse.error(500, "Request interrupted while waiting for the original payment result.");
        } catch (ExecutionException exception) {
            return StoredResponse.error(500, "Original payment processing failed.");
        }
    }

    private void removeExpiredRecords() {
        Instant now = Instant.now();
        records.entrySet().removeIf(entry -> entry.getValue().isExpired(now, ttl));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    static final class StoredResponse {
        private final ResponseSnapshot response;
        private final boolean cacheHit;

        private StoredResponse(ResponseSnapshot response, boolean cacheHit) {
            this.response = response;
            this.cacheHit = cacheHit;
        }

        static StoredResponse original(ResponseSnapshot response) {
            return new StoredResponse(response, false);
        }

        static StoredResponse replayed(ResponseSnapshot response) {
            return new StoredResponse(response, true);
        }

        static StoredResponse conflict(String message) {
            return error(409, message);
        }

        static StoredResponse error(int statusCode, String message) {
            return new StoredResponse(ResponseSnapshot.json(statusCode, "{\"error\":\"" + escapeJson(message) + "\"}"), false);
        }

        ResponseSnapshot response() {
            return response;
        }

        boolean cacheHit() {
            return cacheHit;
        }

        private static String escapeJson(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
