package com.gateproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class HttpTestClient {
    private HttpTestClient() {
    }

    static String get(String host, int port, String path) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            OutputStream output = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return readResponse(socket.getInputStream());
        }
    }

    static String sendRaw(String host, int port, String rawRequest) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.getOutputStream().write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readResponse(socket.getInputStream());
        }
    }

    private static String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
