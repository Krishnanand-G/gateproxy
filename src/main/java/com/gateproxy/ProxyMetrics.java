package com.gateproxy;

import java.util.concurrent.atomic.AtomicLong;

public final class ProxyMetrics {
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong originForwards = new AtomicLong();

    public void recordRequest() {
        requests.incrementAndGet();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public void recordRejected() {
        rejected.incrementAndGet();
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public void recordOriginForward() {
        originForwards.incrementAndGet();
    }

    public long requests() {
        return requests.get();
    }

    public long cacheHits() {
        return cacheHits.get();
    }

    public long cacheMisses() {
        return cacheMisses.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long errors() {
        return errors.get();
    }

    public long originForwards() {
        return originForwards.get();
    }

    public double hitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        if (total == 0) {
            return 0.0;
        }
        return (double) hits / total;
    }

    public String toJson(int cacheSize, int cacheCapacity, boolean demoMode) {
        return "{"
                + "\"requests\":" + requests.get() + ","
                + "\"cacheHits\":" + cacheHits.get() + ","
                + "\"cacheMisses\":" + cacheMisses.get() + ","
                + "\"hitRate\":" + String.format(java.util.Locale.US, "%.4f", hitRate()) + ","
                + "\"originForwards\":" + originForwards.get() + ","
                + "\"rejected\":" + rejected.get() + ","
                + "\"errors\":" + errors.get() + ","
                + "\"cacheSize\":" + cacheSize + ","
                + "\"cacheCapacity\":" + cacheCapacity + ","
                + "\"demoMode\":" + demoMode
                + "}";
    }
}
