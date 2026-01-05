package com.gateproxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyServer implements AutoCloseable {
    private final ProxyConfig config;
    private final OriginForwarder forwarder;
    private final ExecutorService executor;
    private final Semaphore connectionPermits;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public ProxyServer(ProxyConfig config) {
        this.config = config;
        this.forwarder = new OriginForwarder(config.originConnectTimeoutMs(), config.originReadTimeoutMs());
        this.executor = Executors.newFixedThreadPool(config.threadPoolSize());
        this.connectionPermits = new Semaphore(config.maxConcurrentConnections());
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("proxy already running");
        }
        serverSocket = new ServerSocket(config.listenPort());
        Thread acceptThread = new Thread(this::acceptLoop, "gateproxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                if (!connectionPermits.tryAcquire()) {
                    rejectServiceUnavailable(client);
                    continue;
                }
                executor.submit(new ClientHandler(client, config, forwarder, connectionPermits::release));
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
            client.getOutputStream().write(response.getBytes());
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
        executor.shutdownNow();
    }
}
