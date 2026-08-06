package com.gateproxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final ProxyConfig config;
    private final OriginForwarder forwarder;
    private final ResponseCache cache;
    private final ProxyMetrics metrics;
    private final ControlPlane controlPlane;
    private final Runnable onComplete;

    ClientHandler(Socket clientSocket,
                  ProxyConfig config,
                  OriginForwarder forwarder,
                  ResponseCache cache,
                  ProxyMetrics metrics,
                  ControlPlane controlPlane,
                  Runnable onComplete) {
        this.clientSocket = clientSocket;
        this.config = config;
        this.forwarder = forwarder;
        this.cache = cache;
        this.metrics = metrics;
        this.controlPlane = controlPlane;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(config.originReadTimeoutMs());
            HttpRequest request = HttpRequest.parse(clientSocket.getInputStream());
            metrics.recordRequest();
            OutputStream clientOut = clientSocket.getOutputStream();

            if (controlPlane != null && controlPlane.handles(request)) {
                controlPlane.handle(request, clientOut);
                return;
            }

            if (config.demoMode() && !config.isOriginAllowed(request.originHost(), request.originPort())) {
                metrics.recordRejected();
                writeError(403, "Forbidden");
                return;
            }

            if (request.isGet()) {
                serveGet(request, clientOut);
            } else {
                metrics.recordOriginForward();
                forwarder.forward(request, clientOut);
            }
        } catch (MalformedHttpRequestException ex) {
            metrics.recordError();
            writeError(400, "Bad Request");
        } catch (SocketTimeoutException ex) {
            metrics.recordError();
            writeError(504, "Gateway Timeout");
        } catch (ConnectException ex) {
            metrics.recordError();
            writeError(502, "Bad Gateway");
        } catch (IOException ex) {
            // Client disconnect or broken pipe.
        } finally {
            closeQuietly(clientSocket);
            onComplete.run();
        }
    }

    private void serveGet(HttpRequest request, OutputStream clientOut) throws IOException {
        String cacheKey = request.cacheKey();
        CachedResponse cached = cache.get(cacheKey);
        if (cached != null) {
            metrics.recordCacheHit();
            clientOut.write(cached.bytes());
            clientOut.flush();
            return;
        }

        metrics.recordCacheMiss();
        metrics.recordOriginForward();
        byte[] response = forwarder.forwardAndCapture(request);
        boolean hasAuthorization = request.header("authorization") != null;
        boolean hasSetCookie = responseContainsHeader(response, "set-cookie");
        if (isCacheableResponse(response, config.maxCacheEntryBytes(), hasAuthorization, hasSetCookie)
                && !hasNoStore(response)) {
            cache.put(cacheKey, new CachedResponse(response, config.cacheTtlMs()));
        }
        clientOut.write(response);
        clientOut.flush();
    }

    static boolean isCacheableResponse(byte[] response,
                                       int maxBytes,
                                       boolean hasAuthorization,
                                       boolean hasSetCookie) {
        if (hasAuthorization || hasSetCookie) {
            return false;
        }
        if (response == null || response.length < 12 || response.length > maxBytes) {
            return false;
        }
        String statusLine = new String(response, 0, Math.min(response.length, 64), StandardCharsets.US_ASCII);
        int lineEnd = statusLine.indexOf("\r\n");
        if (lineEnd <= 0) {
            return false;
        }
        return statusLine.substring(0, lineEnd).toUpperCase(Locale.ROOT).startsWith("HTTP/1.1 200");
    }

    private static boolean hasNoStore(byte[] response) {
        String headers = headerBlock(response).toLowerCase(Locale.ROOT);
        return headers.contains("cache-control:") && headers.contains("no-store");
    }

    private static boolean responseContainsHeader(byte[] response, String headerName) {
        return headerBlock(response).toLowerCase(Locale.ROOT).contains(headerName.toLowerCase(Locale.ROOT) + ":");
    }

    private static String headerBlock(byte[] response) {
        String text = new String(response, 0, Math.min(response.length, 2048), StandardCharsets.US_ASCII);
        int end = text.indexOf("\r\n\r\n");
        return end >= 0 ? text.substring(0, end) : text;
    }

    private void writeError(int status, String message) {
        try {
            String body = message + "\r\n";
            String response = "HTTP/1.1 " + status + " " + message + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.US_ASCII).length + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body;
            clientSocket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            clientSocket.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
