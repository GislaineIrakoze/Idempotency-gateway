package com.igirepay.gateway;

final class ResponseSnapshot {
    private final int statusCode;
    private final String body;
    private final String contentType;

    private ResponseSnapshot(int statusCode, String body, String contentType) {
        this.statusCode = statusCode;
        this.body = body;
        this.contentType = contentType;
    }

    static ResponseSnapshot json(int statusCode, String body) {
        return new ResponseSnapshot(statusCode, body, "application/json");
    }

    int statusCode() {
        return statusCode;
    }

    String body() {
        return body;
    }

    String contentType() {
        return contentType;
    }
}

