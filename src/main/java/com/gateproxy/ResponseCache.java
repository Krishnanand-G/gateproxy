package com.gateproxy;

import java.util.Iterator;
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
        purgeExpired();
        CachedResponse cached = store.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired(System.currentTimeMillis())) {
            store.remove(key);
            return null;
        }
        return cached;
    }

    public synchronized void put(String key, CachedResponse response) {
        purgeExpired();
        store.put(key, response);
    }

    public synchronized boolean contains(String key) {
        return get(key) != null;
    }

    public synchronized int size() {
        purgeExpired();
        return store.size();
    }

    public int capacity() {
        return capacity;
    }

    synchronized String eldestKey() {
        purgeExpired();
        if (store.isEmpty()) {
            return null;
        }
        return store.keySet().iterator().next();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedResponse>> iterator = store.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedResponse> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
            }
        }
    }
}
