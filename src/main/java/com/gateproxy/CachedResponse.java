package com.gateproxy;

public final class CachedResponse {
    private final byte[] bytes;
    private final long expiresAtMs;

    public CachedResponse(byte[] bytes, long ttlMs) {
        this.bytes = bytes.clone();
        this.expiresAtMs = System.currentTimeMillis() + Math.max(0L, ttlMs);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }

    int size() {
        return bytes.length;
    }
}
