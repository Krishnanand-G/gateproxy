package com.gateproxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final ProxyConfig config;
    private final OriginForwarder forwarder;
    private final ResponseCache cache;
    private final Runnable onComplete;

    ClientHandler(Socket clientSocket,
                  ProxyConfig config,
                  OriginForwarder forwarder,
                  ResponseCache cache,
                  Runnable onComplete) {
        this.clientSocket = clientSocket;
        this.config = config;
        this.forwarder = forwarder;
        this.cache = cache;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(config.originReadTimeoutMs());
            HttpRequest request = HttpRequest.parse(clientSocket.getInputStream());
            OutputStream clientOut = clientSocket.getOutputStream();

            if (request.isGet()) {
                serveGet(request, clientOut);
            } else {
                forwarder.forward(request, clientOut);
            }
        } catch (MalformedHttpRequestException ex) {
            writeError(400, "Bad Request");
        } catch (SocketTimeoutException ex) {
            writeError(504, "Gateway Timeout");
        } catch (ConnectException ex) {
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
            clientOut.write(cached.bytes());
            clientOut.flush();
            return;
        }

        byte[] response = forwarder.forwardAndCapture(request);
        if (isCacheable(response)) {
            cache.put(cacheKey, new CachedResponse(response));
        }
        clientOut.write(response);
        clientOut.flush();
    }

    private static boolean isCacheable(byte[] response) {
        if (response == null || response.length < 12) {
            return false;
        }
        String statusLine = new String(response, 0, Math.min(response.length, 64), StandardCharsets.US_ASCII);
        int lineEnd = statusLine.indexOf("\r\n");
        if (lineEnd <= 0) {
            return false;
        }
        return statusLine.substring(0, lineEnd).startsWith("HTTP/1.1 200");
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
