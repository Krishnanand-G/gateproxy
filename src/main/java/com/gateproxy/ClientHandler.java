package com.gateproxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
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
        } catch (IOException ex) {
            // Client disconnect or origin failure.
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
        cache.put(cacheKey, new CachedResponse(response));
        clientOut.write(response);
        clientOut.flush();
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
