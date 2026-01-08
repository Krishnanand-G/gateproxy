package com.gateproxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateProxyIntegrationTest {
    private MockOriginServer origin;
    private ProxyServer proxy;

    @BeforeEach
    void setUp() throws Exception {
        origin = MockOriginServer.fast();
        origin.register("/hello", "hello-world");
        origin.register("/one", "one");
        origin.register("/two", "two");
        origin.register("/three", "three");

        ProxyConfig config = new ProxyConfig(0, 8, 16, 2, 1000, 2000);
        proxy = new ProxyServer(config);
        proxy.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        proxy.close();
        origin.close();
    }

    @Test
    void cacheMissThenHit() throws IOException {
        int proxyPort = proxy.listenPort();
        String first = HttpTestClient.get("127.0.0.1", proxyPort, "http://127.0.0.1:" + origin.port() + "/hello");
        String second = HttpTestClient.get("127.0.0.1", proxyPort, "http://127.0.0.1:" + origin.port() + "/hello");

        assertTrue(first.contains("hello-world"));
        assertTrue(second.contains("hello-world"));
        assertEquals(1, origin.requestCount());
    }

    @Test
    void evictionRemovesOldestUnusedEntry() throws IOException {
        int proxyPort = proxy.listenPort();
        String originBase = "http://127.0.0.1:" + origin.port();

        HttpTestClient.get("127.0.0.1", proxyPort, originBase + "/one");
        HttpTestClient.get("127.0.0.1", proxyPort, originBase + "/two");
        HttpTestClient.get("127.0.0.1", proxyPort, originBase + "/one");
        HttpTestClient.get("127.0.0.1", proxyPort, originBase + "/three");

        assertEquals(3, origin.requestCount());
        HttpTestClient.get("127.0.0.1", proxyPort, originBase + "/two");
        assertEquals(4, origin.requestCount());
    }

    @Test
    void concurrentClientsReceiveResponses() throws Exception {
        int proxyPort = proxy.listenPort();
        String url = "http://127.0.0.1:" + origin.port() + "/hello";
        ExecutorService clients = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> HttpTestClient.get("127.0.0.1", proxyPort, url));
        }
        List<Future<String>> results = clients.invokeAll(tasks, 10, TimeUnit.SECONDS);
        clients.shutdownNow();

        for (Future<String> result : results) {
            assertTrue(result.get().contains("hello-world"));
        }
    }

    @Test
    void malformedRequestReturns400() throws IOException {
        int proxyPort = proxy.listenPort();
        String response = HttpTestClient.sendRaw("127.0.0.1", proxyPort, "NOTHTTP\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 400"));
    }

    @Test
    void originReadTimeoutReturns504() throws Exception {
        origin.close();
        origin = MockOriginServer.hanging();

        ProxyConfig config = new ProxyConfig(0, 4, 8, 8, 1000, 500);
        proxy.close();
        proxy = new ProxyServer(config);
        proxy.start();

        long start = System.nanoTime();
        String response = HttpTestClient.get("127.0.0.1", proxy.listenPort(),
                "http://127.0.0.1:" + origin.port() + "/slow");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(response.startsWith("HTTP/1.1 504"));
        assertTrue(elapsedMs < 5000, "expected timeout under 5s, was " + elapsedMs + "ms");
    }
}
