package com.gateproxy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ProxyConfig {
    private final String bindHost;
    private final int listenPort;
    private final int threadPoolSize;
    private final int maxConcurrentConnections;
    private final int cacheCapacity;
    private final long cacheTtlMs;
    private final int maxCacheEntryBytes;
    private final int originConnectTimeoutMs;
    private final int originReadTimeoutMs;
    private final boolean demoMode;
    private final Set<String> allowedOrigins;

    public ProxyConfig(int listenPort,
                       int threadPoolSize,
                       int maxConcurrentConnections,
                       int cacheCapacity,
                       int originConnectTimeoutMs,
                       int originReadTimeoutMs) {
        this("0.0.0.0",
                listenPort,
                threadPoolSize,
                maxConcurrentConnections,
                cacheCapacity,
                60_000L,
                256 * 1024,
                originConnectTimeoutMs,
                originReadTimeoutMs,
                false,
                Set.of());
    }

    public ProxyConfig(String bindHost,
                       int listenPort,
                       int threadPoolSize,
                       int maxConcurrentConnections,
                       int cacheCapacity,
                       long cacheTtlMs,
                       int maxCacheEntryBytes,
                       int originConnectTimeoutMs,
                       int originReadTimeoutMs,
                       boolean demoMode,
                       Set<String> allowedOrigins) {
        this.bindHost = bindHost;
        this.listenPort = listenPort;
        this.threadPoolSize = threadPoolSize;
        this.maxConcurrentConnections = maxConcurrentConnections;
        this.cacheCapacity = cacheCapacity;
        this.cacheTtlMs = cacheTtlMs;
        this.maxCacheEntryBytes = maxCacheEntryBytes;
        this.originConnectTimeoutMs = originConnectTimeoutMs;
        this.originReadTimeoutMs = originReadTimeoutMs;
        this.demoMode = demoMode;
        this.allowedOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(allowedOrigins));
    }

    public static ProxyConfig defaults() {
        return fromEnvironment();
    }

    public static ProxyConfig fromEnvironment() {
        String bindHost = env("BIND_HOST", "0.0.0.0");
        int listenPort = envInt("PORT", envInt("LISTEN_PORT", 8080));
        int threadPoolSize = envInt("THREAD_POOL_SIZE", 32);
        int maxConcurrent = envInt("MAX_CONCURRENT_CONNECTIONS", 64);
        int cacheCapacity = envInt("CACHE_CAPACITY", 128);
        long cacheTtlMs = envLong("CACHE_TTL_MS", 60_000L);
        int maxCacheEntryBytes = envInt("MAX_CACHE_ENTRY_BYTES", 256 * 1024);
        int connectTimeout = envInt("ORIGIN_CONNECT_TIMEOUT_MS", 3000);
        int readTimeout = envInt("ORIGIN_READ_TIMEOUT_MS", 5000);
        boolean demoMode = envBool("DEMO_MODE", false);
        Set<String> allowed = parseAllowedOrigins(env("ALLOWED_ORIGINS", ""));
        return new ProxyConfig(
                bindHost,
                listenPort,
                threadPoolSize,
                maxConcurrent,
                cacheCapacity,
                cacheTtlMs,
                maxCacheEntryBytes,
                connectTimeout,
                readTimeout,
                demoMode,
                allowed);
    }

    public String bindHost() {
        return bindHost;
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

    public long cacheTtlMs() {
        return cacheTtlMs;
    }

    public int maxCacheEntryBytes() {
        return maxCacheEntryBytes;
    }

    public int originConnectTimeoutMs() {
        return originConnectTimeoutMs;
    }

    public int originReadTimeoutMs() {
        return originReadTimeoutMs;
    }

    public boolean demoMode() {
        return demoMode;
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    public boolean isOriginAllowed(String host, int port) {
        if (!demoMode) {
            return true;
        }
        if (allowedOrigins.isEmpty()) {
            return false;
        }
        String key = host.toLowerCase(Locale.ROOT) + ":" + port;
        String hostOnly = host.toLowerCase(Locale.ROOT);
        return allowedOrigins.contains(key) || allowedOrigins.contains(hostOnly);
    }

    public ProxyConfig withAllowedOrigin(String host, int port) {
        Set<String> next = new LinkedHashSet<>(allowedOrigins);
        next.add(host.toLowerCase(Locale.ROOT) + ":" + port);
        next.add(host.toLowerCase(Locale.ROOT));
        return new ProxyConfig(
                bindHost,
                listenPort,
                threadPoolSize,
                maxConcurrentConnections,
                cacheCapacity,
                cacheTtlMs,
                maxCacheEntryBytes,
                originConnectTimeoutMs,
                originReadTimeoutMs,
                demoMode,
                next);
    }

    public ProxyConfig withDemoMode(boolean enabled) {
        return new ProxyConfig(
                bindHost,
                listenPort,
                threadPoolSize,
                maxConcurrentConnections,
                cacheCapacity,
                cacheTtlMs,
                maxCacheEntryBytes,
                originConnectTimeoutMs,
                originReadTimeoutMs,
                enabled,
                allowedOrigins);
    }

    private static Set<String> parseAllowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> origins = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(origins::add);
        return origins;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static long envLong(String key, long fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
