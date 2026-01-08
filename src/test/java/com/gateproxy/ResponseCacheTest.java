package com.gateproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseCacheTest {
    @Test
    void cacheHitAfterPut() {
        ResponseCache cache = new ResponseCache(8);
        cache.put("GET /a", new CachedResponse("one".getBytes()));
        assertTrue(cache.contains("GET /a"));
        assertEquals("one", new String(cache.get("GET /a").bytes()));
    }

    @Test
    void cacheMissReturnsNull() {
        ResponseCache cache = new ResponseCache(8);
        assertNull(cache.get("GET /missing"));
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        ResponseCache cache = new ResponseCache(2);
        cache.put("GET /a", new CachedResponse("a".getBytes()));
        cache.put("GET /b", new CachedResponse("b".getBytes()));
        cache.get("GET /a");
        cache.put("GET /c", new CachedResponse("c".getBytes()));

        assertFalse(cache.contains("GET /b"));
        assertTrue(cache.contains("GET /a"));
        assertTrue(cache.contains("GET /c"));
        assertEquals(2, cache.size());
    }
}
