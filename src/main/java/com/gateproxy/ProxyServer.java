package com.gateproxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyServer implements AutoCloseable {
    private final ProxyConfig config;
    private final OriginForwarder forwarder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public ProxyServer(ProxyConfig config) {
        this.config = config;
        this.forwarder = new OriginForwarder(config.originConnectTimeoutMs(), config.originReadTimeoutMs());
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
                Thread handler = new Thread(
                        new ClientHandler(client, config, forwarder, () -> { }),
                        "gateproxy-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException ex) {
                if (running.get()) {
                    // Accept loop ends when server socket closes.
                }
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
    }
}
