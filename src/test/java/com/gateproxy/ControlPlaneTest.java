package com.gateproxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneTest {
    private DemoOriginServer origin;
    private ProxyServer proxy;

    @BeforeEach
    void setUp() throws Exception {
        origin = DemoOriginServer.start();
        ProxyConfig config = new ProxyConfig(
                "0.0.0.0",
                0,
                8,
                16,
                8,
                60_000,
                256 * 1024,
                1000,
                2000,
                true,
                Set.of("127.0.0.1:" + origin.port()));
        proxy = new ProxyServer(config, origin);
        proxy.start();
    }

    @AfterEach
    void tearDown() {
        proxy.close();
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        String response = HttpTestClient.get("127.0.0.1", proxy.listenPort(), "/health");
        assertTrue(response.contains("\"status\":\"ok\""));
    }

    @Test
    void metricsEndpointReturnsJson() throws Exception {
        String response = HttpTestClient.get("127.0.0.1", proxy.listenPort(), "/metrics");
        assertTrue(response.contains("\"demoMode\":true"));
        assertTrue(response.contains("cacheHits"));
    }

    @Test
    void probeMissThenHit() throws Exception {
        String first = HttpTestClient.get("127.0.0.1", proxy.listenPort(), "/api/probe");
        String second = HttpTestClient.get("127.0.0.1", proxy.listenPort(), "/api/probe");
        assertTrue(first.contains("\"cacheHit\":false"));
        assertTrue(second.contains("\"cacheHit\":true"));
    }

    @Test
    void demoModeBlocksForeignOrigin() throws Exception {
        String response = HttpTestClient.get(
                "127.0.0.1",
                proxy.listenPort(),
                "http://example.com/");
        assertTrue(response.startsWith("HTTP/1.1 403"));
    }
}
