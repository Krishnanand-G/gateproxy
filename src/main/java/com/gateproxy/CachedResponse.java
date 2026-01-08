package com.gateproxy;

public final class CachedResponse {
    private final byte[] bytes;

    public CachedResponse(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }
}
