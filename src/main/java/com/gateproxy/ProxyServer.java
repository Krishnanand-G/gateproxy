package com.gateproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyServer implements AutoCloseable {
    private final ProxyConfig config;
    private final OriginForwarder forwarder;
    private final ResponseCache cache;
    private final ProxyMetrics metrics;
    private final ControlPlane controlPlane;
    private final DemoOriginServer demoOrigin;
    private final ExecutorService executor;
    private final Semaphore connectionPermits;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public ProxyServer(ProxyConfig config) {
        this(config, null);
    }

    public ProxyServer(ProxyConfig config, DemoOriginServer demoOrigin) {
        ProxyConfig effective = config;
        if (config.demoMode() && demoOrigin != null) {
            effective = config.withAllowedOrigin("127.0.0.1", demoOrigin.port())
                    .withAllowedOrigin("localhost", demoOrigin.port());
        }
        this.config = effective;
        this.demoOrigin = demoOrigin;
        this.forwarder = new OriginForwarder(effective.originConnectTimeoutMs(), effective.originReadTimeoutMs());
        this.cache = new ResponseCache(effective.cacheCapacity());
        this.metrics = new ProxyMetrics();
        this.controlPlane = new ControlPlane(effective, cache, metrics, forwarder, demoOrigin);
        this.executor = Executors.newFixedThreadPool(effective.threadPoolSize());
        this.connectionPermits = new Semaphore(effective.maxConcurrentConnections());
    }

    public ResponseCache cache() {
        return cache;
    }

    public ProxyMetrics metrics() {
        return metrics;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("proxy already running");
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.bindHost(), config.listenPort()));
        Thread acceptThread = new Thread(this::acceptLoop, "gateproxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                if (!connectionPermits.tryAcquire()) {
                    metrics.recordRejected();
                    rejectServiceUnavailable(client);
                    continue;
                }
                executor.submit(new ClientHandler(
                        client,
                        config,
                        forwarder,
                        cache,
                        metrics,
                        controlPlane,
                        connectionPermits::release));
            } catch (IOException ex) {
                if (running.get()) {
                    // Accept loop ends when server socket closes.
                }
            }
        }
    }

    private static void rejectServiceUnavailable(Socket client) {
        try {
            String body = "Service Unavailable\r\n";
            String response = "HTTP/1.1 503 Service Unavailable\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body;
            client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    public int listenPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (demoOrigin != null) {
            try {
                demoOrigin.close();
            } catch (IOException ignored) {
            }
        }
    }
}
