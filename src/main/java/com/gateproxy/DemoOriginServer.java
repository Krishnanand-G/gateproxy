package com.gateproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class DemoOriginServer implements AutoCloseable {
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    static DemoOriginServer start() throws IOException {
        DemoOriginServer server = new DemoOriginServer();
        server.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        String body = "{\"service\":\"gateproxy-demo-origin\",\"message\":\"cached payload\",\"ok\":true}";
        server.register("/demo/resource", "application/json", body);
        server.acceptThread = new Thread(server::acceptLoop, "demo-origin");
        server.acceptThread.setDaemon(true);
        server.acceptThread.start();
        return server;
    }

    void register(String path, String contentType, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Cache-Control: public, max-age=60\r\n"
                + "Connection: close\r\n\r\n"
                + body;
        responses.put(path, response.getBytes(StandardCharsets.US_ASCII));
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    int requestCount() {
        return requestCount.get();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try (Socket client = serverSocket.accept()) {
                requestCount.incrementAndGet();
                handle(client);
            } catch (IOException ex) {
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
        String[] parts = requestLine.split("\\s+");
        String target = parts.length > 1 ? parts[1] : "/";
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        skipHeaders(input);
        byte[] response = responses.getOrDefault(path, notFound());
        OutputStream output = client.getOutputStream();
        output.write(response);
        output.flush();
    }

    private static byte[] notFound() {
        String body = "{\"error\":\"not_found\"}";
        String response = "HTTP/1.1 404 Not Found\r\n"
                + "Content-Type: application/json\r\n"
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
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '\r') {
                    builder.setLength(builder.length() - 1);
                }
                return builder.toString();
            }
            builder.append((char) current);
            previous = current;
        }
        return builder.isEmpty() ? null : builder.toString();
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
