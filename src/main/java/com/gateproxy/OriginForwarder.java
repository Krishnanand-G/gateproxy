package com.gateproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class OriginForwarder {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public OriginForwarder(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public byte[] forward(HttpRequest request) throws IOException {
        try (Socket origin = new Socket()) {
            origin.connect(new InetSocketAddress(request.originHost(), request.originPort()), connectTimeoutMs);
            origin.setSoTimeout(readTimeoutMs);

            OutputStream originOut = origin.getOutputStream();
            originOut.write(buildOriginRequest(request).getBytes(StandardCharsets.US_ASCII));
            originOut.write(request.body());
            originOut.flush();

            return readAll(origin.getInputStream());
        }
    }

    private static String buildOriginRequest(HttpRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.method()).append(' ')
                .append(request.originPath()).append(' ')
                .append(request.version()).append("\r\n");

        boolean hasHost = false;
        for (var entry : request.headers().entrySet()) {
            if ("host".equals(entry.getKey())) {
                hasHost = true;
            }
            if ("proxy-connection".equals(entry.getKey())) {
                continue;
            }
            builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        if (!hasHost) {
            builder.append("Host: ").append(request.originHost());
            if (request.originPort() != 80) {
                builder.append(':').append(request.originPort());
            }
            builder.append("\r\n");
        }
        builder.append("Connection: close\r\n\r\n");
        return builder.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
