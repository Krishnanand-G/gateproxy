package com.gateproxy;

public final class ProxyConfig {
    private final int listenPort;
    private final int threadPoolSize;
    private final int maxConcurrentConnections;
    private final int cacheCapacity;
    private final int originConnectTimeoutMs;
    private final int originReadTimeoutMs;

    public ProxyConfig(int listenPort,
                       int threadPoolSize,
                       int maxConcurrentConnections,
                       int cacheCapacity,
                       int originConnectTimeoutMs,
                       int originReadTimeoutMs) {
        this.listenPort = listenPort;
        this.threadPoolSize = threadPoolSize;
        this.maxConcurrentConnections = maxConcurrentConnections;
        this.cacheCapacity = cacheCapacity;
        this.originConnectTimeoutMs = originConnectTimeoutMs;
        this.originReadTimeoutMs = originReadTimeoutMs;
    }

    public static ProxyConfig defaults() {
        return new ProxyConfig(8080, 32, 64, 128, 3000, 5000);
    }

    public int listenPort() {
        return listenPort;
    }

    public int threadPoolSize() {
        return threadPoolSize;
    }

    public int maxConcurrentConnections() {
        return maxConcurrentConnections;
    }

    public int cacheCapacity() {
        return cacheCapacity;
    }

    public int originConnectTimeoutMs() {
        return originConnectTimeoutMs;
    }

    public int originReadTimeoutMs() {
        return originReadTimeoutMs;
    }
}
