package com.gateproxy;

public final class Main {
    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.defaults();
        if (args.length >= 1) {
            config = new ProxyConfig(
                    Integer.parseInt(args[0]),
                    config.maxConnections(),
                    config.threadPoolSize(),
                    config.cacheCapacity(),
                    config.originTimeoutMillis());
        }
        ProxyServer server = new ProxyServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "gateproxy-shutdown"));
        server.start();
    }
}
