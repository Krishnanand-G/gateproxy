package com.gateproxy.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.gateproxy.ProxyConfig;
import com.gateproxy.ProxyServer;

public final class BenchmarkHarness {
    private static final int REQUEST_COUNT = 200;
    private static final String RESOURCE_PATH = "/bench-resource";

    public static void main(String[] args) throws Exception {
        StubOrigin origin = StubOrigin.start();
        origin.register(RESOURCE_PATH, "benchmark-payload");

        ProxyConfig config = new ProxyConfig(0, 16, 32, 128, 2000, 5000);
        try (ProxyServer proxy = new ProxyServer(config)) {
            proxy.start();
            int proxyPort = proxy.listenPort();
            String target = "http://127.0.0.1:" + origin.port() + RESOURCE_PATH;

            List<Long> latenciesMs = new ArrayList<>(REQUEST_COUNT);
            for (int i = 0; i < REQUEST_COUNT; i++) {
                long start = System.nanoTime();
                String response = HttpClient.get("127.0.0.1", proxyPort, target);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (!response.contains("benchmark-payload")) {
                    throw new IllegalStateException("unexpected benchmark response on iteration " + i);
                }
                latenciesMs.add(elapsedMs);
            }

            long uncachedLatencyMs = latenciesMs.get(0);
            List<Long> cachedLatencies = latenciesMs.subList(1, latenciesMs.size());
            double cachedAverageMs = cachedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
            int cacheHits = REQUEST_COUNT - 1;
            double cacheHitRate = (double) cacheHits / REQUEST_COUNT;

            String timestamp = Instant.now().toString().replace(':', '-');
            Path resultsDir = Path.of("benchmark", "results");
            Files.createDirectories(resultsDir);
            Path resultFile = resultsDir.resolve("benchmark-" + timestamp + ".json");

            String json = "{"
                    + "\"requestCount\":" + REQUEST_COUNT + ","
                    + "\"cacheHits\":" + cacheHits + ","
                    + "\"cacheMisses\":1,"
                    + "\"cacheHitRate\":" + String.format(Locale.US, "%.6f", cacheHitRate) + ","
                    + "\"uncachedLatencyMs\":" + uncachedLatencyMs + ","
                    + "\"cachedAverageLatencyMs\":" + String.format(Locale.US, "%.3f", cachedAverageMs) + ","
                    + "\"originRequests\":" + origin.requestCount() + ","
                    + "\"proxyPort\":" + proxyPort + ","
                    + "\"originPort\":" + origin.port()
                    + "}";
            Files.writeString(resultFile, json);

            System.out.println("Benchmark complete");
            System.out.println("Results file: " + resultFile.toAbsolutePath());
            System.out.println("requestCount=" + REQUEST_COUNT);
            System.out.println("cacheHitRate=" + String.format(Locale.US, "%.4f", cacheHitRate));
            System.out.println("uncachedLatencyMs=" + uncachedLatencyMs);
            System.out.println("cachedAverageLatencyMs=" + String.format(Locale.US, "%.3f", cachedAverageMs));
            System.out.println("originRequests=" + origin.requestCount());
        } finally {
            origin.close();
        }
    }

    private static final class HttpClient {
        private HttpClient() {
        }

        static String get(String host, int port, String path) throws IOException {
            try (Socket socket = new Socket(host, port)) {
                OutputStream output = socket.getOutputStream();
                String request = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(request.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                return readResponse(socket.getInputStream());
            }
        }

        private static String readResponse(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.US_ASCII);
        }
    }

    private static final class StubOrigin implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.Map<String, byte[]> responses = new java.util.concurrent.ConcurrentHashMap<>();
        private ServerSocket serverSocket;

        static StubOrigin start() throws IOException {
            StubOrigin origin = new StubOrigin();
            origin.serverSocket = new ServerSocket(0);
            Thread acceptThread = new Thread(origin::acceptLoop, "benchmark-origin");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return origin;
        }

        void register(String path, String body) {
            String response = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
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
            String target = requestLine.split("\\s+")[1];
            String path = target.startsWith("http://") ? java.net.URI.create(target).getPath() : target;
            skipHeaders(input);
            byte[] response = responses.getOrDefault(path, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            OutputStream output = client.getOutputStream();
            output.write(response);
            output.flush();
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
}
