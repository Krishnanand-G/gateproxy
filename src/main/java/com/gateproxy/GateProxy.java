package com.gateproxy;

public final class GateProxy {
    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.defaults();
        try (ProxyServer server = new ProxyServer(config)) {
            server.start();
            System.out.println("GateProxy listening on port " + config.listenPort());
            Thread.currentThread().join();
        }
    }
}
