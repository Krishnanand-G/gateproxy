package com.gateproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class MockOriginServer implements AutoCloseable {
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    private final long responseDelayMs;
    private final boolean hangAfterAccept;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    MockOriginServer(long responseDelayMs, boolean hangAfterAccept) {
        this.responseDelayMs = responseDelayMs;
        this.hangAfterAccept = hangAfterAccept;
    }

    static MockOriginServer fast() throws IOException {
        MockOriginServer server = new MockOriginServer(0, false);
        server.start();
        return server;
    }

    static MockOriginServer hanging() throws IOException {
        MockOriginServer server = new MockOriginServer(0, true);
        server.start();
        return server;
    }

    void register(String path, String body) {
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n\r\n"
                + body;
        responses.put(path, response.getBytes(StandardCharsets.US_ASCII));
    }

    int requestCount() {
        return requestCount.get();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void start() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = new Thread(this::acceptLoop, "mock-origin");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try (Socket client = serverSocket.accept()) {
                requestCount.incrementAndGet();
                if (hangAfterAccept) {
                    Thread.sleep(60_000);
                    continue;
                }
                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs);
                }
                handle(client);
            } catch (IOException | InterruptedException ex) {
                if (serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        String requestLine = readLine(input);
        if (requestLine == null) {
            return;
        }
        String target = requestLine.split("\\s+")[1];
        String path = toPath(target);
        skipHeaders(input);
        byte[] response = responses.getOrDefault(path, notFound(path));
        OutputStream output = client.getOutputStream();
        output.write(response);
        output.flush();
    }

    private static String toPath(String target) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            URI uri = URI.create(target);
            String path = uri.getPath();
            return path == null || path.isEmpty() ? "/" : path;
        }
        int queryIndex = target.indexOf('?');
        return queryIndex >= 0 ? target.substring(0, queryIndex) : target;
    }

    private static byte[] notFound(String path) {
        String body = "missing " + path;
        String response = "HTTP/1.1 404 Not Found\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n"
                + body;
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\r') {
                    builder.setLength(builder.length() - 1);
                }
                return builder.toString();
            }
            builder.append((char) current);
            previous = current;
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static void skipHeaders(InputStream input) throws IOException {
        int state = 0;
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (state == 0 && current == '\n' && previous == '\r') {
                state = 1;
            } else if (state == 1 && current == '\n' && previous == '\r') {
                return;
            } else if (current != '\r') {
                state = 0;
            }
            previous = current;
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
