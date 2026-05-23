package com.igirepay.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

final class IdempotencyRecord {
    private final String requestHash;
    private final Instant createdAt;
    private final CompletableFuture<ResponseSnapshot> result = new CompletableFuture<>();

    IdempotencyRecord(String requestHash, Instant createdAt) {
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    String requestHash() {
        return requestHash;
    }

    CompletableFuture<ResponseSnapshot> result() {
        return result;
    }

    boolean isCompleted() {
        return result.isDone();
    }

    boolean isExpired(Instant now, Duration ttl) {
        return isCompleted() && createdAt.plus(ttl).isBefore(now);
    }
}

