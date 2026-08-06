package com.gateproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
final class ControlPlane {
    private final ProxyConfig config;
    private final ResponseCache cache;
    private final ProxyMetrics metrics;
    private final OriginForwarder forwarder;
    private final DemoOriginServer demoOrigin;
    private final byte[] indexHtml;

    ControlPlane(ProxyConfig config,
                 ResponseCache cache,
                 ProxyMetrics metrics,
                 OriginForwarder forwarder,
                 DemoOriginServer demoOrigin) {
        this.config = config;
        this.cache = cache;
        this.metrics = metrics;
        this.forwarder = forwarder;
        this.demoOrigin = demoOrigin;
        this.indexHtml = loadIndexHtml();
    }

    boolean handles(HttpRequest request) {
        String target = request.target();
        // Absolute-form targets are forward-proxy traffic, never the control plane.
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return false;
        }
        String path = localPath(request);
        return "/".equals(path)
                || "/health".equals(path)
                || "/metrics".equals(path)
                || "/api/probe".equals(path)
                || "/favicon.ico".equals(path);
    }

    void handle(HttpRequest request, OutputStream clientOut) throws IOException {
        String path = localPath(request);
        switch (path) {
            case "/health" -> writeJson(clientOut, 200, "{\"status\":\"ok\"}");
            case "/metrics" -> writeJson(clientOut, 200,
                    metrics.toJson(cache.size(), cache.capacity(), config.demoMode()));
            case "/api/probe" -> writeJson(clientOut, 200, runProbe());
            case "/favicon.ico" -> writeBytes(clientOut, 204, "image/x-icon", new byte[0]);
            default -> writeBytes(clientOut, 200, "text/html; charset=utf-8", indexHtml);
        }
    }

    private String runProbe() throws IOException {
        if (demoOrigin == null) {
            return "{\"ok\":false,\"error\":\"demo_origin_unavailable\"}";
        }
        String target = "http://127.0.0.1:" + demoOrigin.port() + "/demo/resource";
        String cacheKey = "GET " + target;
        long start = System.nanoTime();
        boolean hit;
        byte[] response;
        CachedResponse cached = cache.get(cacheKey);
        if (cached != null) {
            hit = true;
            metrics.recordCacheHit();
            response = cached.bytes();
        } else {
            hit = false;
            metrics.recordCacheMiss();
            metrics.recordOriginForward();
            HttpRequest probeRequest = HttpRequest.parse(new java.io.ByteArrayInputStream(
                    ("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + demoOrigin.port()
                            + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII)));
            response = forwarder.forwardAndCapture(probeRequest);
            if (ClientHandler.isCacheableResponse(response, config.maxCacheEntryBytes(), false, false)) {
                cache.put(cacheKey, new CachedResponse(response, config.cacheTtlMs()));
            }
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        String bodyPreview = extractBody(response);
        return "{"
                + "\"ok\":true,"
                + "\"cacheHit\":" + hit + ","
                + "\"latencyMs\":" + latencyMs + ","
                + "\"originRequests\":" + demoOrigin.requestCount() + ","
                + "\"cacheSize\":" + cache.size() + ","
                + "\"payload\":" + jsonString(bodyPreview)
                + "}";
    }

    private static String localPath(HttpRequest request) {
        String target = request.target();
        if (target.startsWith("http://") || target.startsWith("https://")) {
            URI uri = URI.create(target);
            String path = uri.getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        }
        int query = target.indexOf('?');
        return query >= 0 ? target.substring(0, query) : target;
    }

    private static String extractBody(byte[] response) {
        String text = new String(response, StandardCharsets.UTF_8);
        int idx = text.indexOf("\r\n\r\n");
        if (idx < 0) {
            return "";
        }
        return text.substring(idx + 4);
    }

    private static String jsonString(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        writeBytes(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            default -> "OK";
        };
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static byte[] loadIndexHtml() {
        try (InputStream in = ControlPlane.class.getResourceAsStream("/demo/index.html")) {
            if (in == null) {
                return fallbackHtml().getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            return fallbackHtml().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String fallbackHtml() {
        return "<!doctype html><html><body><h1>GateProxy</h1><p>Demo UI missing from classpath.</p></body></html>";
    }
}
