package com.gateproxy;

public final class GateProxy {
    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.fromEnvironment();
        DemoOriginServer demoOrigin = null;
        if (config.demoMode()) {
            demoOrigin = DemoOriginServer.start();
            config = config.withAllowedOrigin("127.0.0.1", demoOrigin.port())
                    .withAllowedOrigin("localhost", demoOrigin.port());
        }

        try (ProxyServer server = new ProxyServer(config, demoOrigin)) {
            server.start();
            System.out.println("GateProxy listening on " + config.bindHost() + ":" + server.listenPort()
                    + (config.demoMode() ? " (demo mode)" : ""));
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            Thread.currentThread().join();
        }
    }
}
