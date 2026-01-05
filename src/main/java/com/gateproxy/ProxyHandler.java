package com.gateproxy;

import java.io.IOException;
import java.net.Socket;

final class ProxyHandler {
    private final ProxyConfig config;

    ProxyHandler(ProxyConfig config) {
        this.config = config;
    }

    void handle(Socket client) throws IOException {
        client.getInputStream().read();
        client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".getBytes());
        client.getOutputStream().flush();
    }
}
