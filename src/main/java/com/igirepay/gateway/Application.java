package com.igirepay.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

public final class Application {
    private static final int DEFAULT_PORT = 8080;
    private static final long DEFAULT_TTL_SECONDS = 86_400;

    private Application() {
    }

    public static void main(String[] args) throws IOException {
        int port = readInt("PORT", DEFAULT_PORT);
        long ttlSeconds = readLong("IDEMPOTENCY_TTL_SECONDS", DEFAULT_TTL_SECONDS);

        IdempotencyStore store = new IdempotencyStore(Duration.ofSeconds(ttlSeconds));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Application::handleRoot);
        server.createContext("/process-payment", new PaymentHandler(store));
        server.createContext("/health", Application::handleHealth);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.printf("Idempotency Gateway running on http://localhost:%d%n", port);
        System.out.printf("Idempotency TTL: %d seconds%n", ttlSeconds);
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "{\"error\":\"Not found.\"}");
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"Method not allowed.\"}");
            return;
        }

        String body = """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>IgirePay Idempotency Gateway</title>
                    <style>
                        body {
                            margin: 0;
                            font-family: Arial, sans-serif;
                            background: #f6f8fb;
                            color: #172033;
                        }
                        main {
                            max-width: 860px;
                            margin: 0 auto;
                            padding: 48px 20px;
                        }
                        h1 {
                            margin: 0 0 10px;
                            font-size: 34px;
                        }
                        p {
                            line-height: 1.6;
                        }
                        code, pre {
                            background: #101828;
                            color: #e6edf7;
                            border-radius: 6px;
                        }
                        code {
                            padding: 2px 6px;
                        }
                        pre {
                            overflow-x: auto;
                            padding: 18px;
                        }
                        .status {
                            display: inline-block;
                            margin: 18px 0;
                            padding: 8px 12px;
                            border-radius: 6px;
                            background: #e6f7ee;
                            color: #11603a;
                            font-weight: 700;
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <h1>IgirePay Idempotency Gateway</h1>
                        <div class="status">API running</div>
                        <p>This Java service prevents double charging by saving the first response for each <code>Idempotency-Key</code> and replaying it for safe retries.</p>
                        <p>Required endpoint: <code>POST /process-payment</code></p>
                        <pre>curl -i -X POST http://localhost:8080/process-payment \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: demo-100" \\
  -d "{\\"amount\\": 100, \\"currency\\": \\"RWF\\"}"</pre>
                        <p>Health check: <code>GET /health</code></p>
                    </main>
                </body>
                </html>
                """;

        send(exchange, 200, body, "text/html; charset=utf-8");
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"Method not allowed.\"}");
            return;
        }

        send(exchange, 200, "{\"status\":\"ok\"}");
    }

    private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
        send(exchange, statusCode, body, "application/json");
    }

    private static void send(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(responseBytes);
        }
    }

    private static int readInt(String name, int fallback) {
        String value = readConfig(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readLong(String name, long fallback) {
        String value = readConfig(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readConfig(String name) {
        String propertyValue = System.getProperty(name);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        return System.getenv(name);
    }
}
