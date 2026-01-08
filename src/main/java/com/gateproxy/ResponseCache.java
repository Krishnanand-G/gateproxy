package com.gateproxy;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ResponseCache {
    private final int capacity;
    private final LinkedHashMap<String, CachedResponse> store;

    public ResponseCache(int capacity) {
        this.capacity = capacity;
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                return size() > ResponseCache.this.capacity;
            }
        };
    }

    public synchronized CachedResponse get(String key) {
        return store.get(key);
    }

    public synchronized void put(String key, CachedResponse response) {
        store.put(key, response);
    }

    public synchronized boolean contains(String key) {
        return store.containsKey(key);
    }

    public synchronized int size() {
        return store.size();
    }

    synchronized String eldestKey() {
        if (store.isEmpty()) {
            return null;
        }
        return store.keySet().iterator().next();
    }
}
