package com.gateproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpRequest {
    private final String method;
    private final String target;
    private final String version;
    private final Map<String, String> headers;
    private final byte[] body;

    private HttpRequest(String method,
                        String target,
                        String version,
                        Map<String, String> headers,
                        byte[] body) {
        this.method = method;
        this.target = target;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    public String method() {
        return method;
    }

    public String target() {
        return target;
    }

    public String version() {
        return version;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isGet() {
        return "GET".equalsIgnoreCase(method);
    }

    public String cacheKey() {
        return method.toUpperCase(Locale.ROOT) + " " + target;
    }

    public static HttpRequest parse(InputStream input) throws IOException {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int state = 0;
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            headerBuffer.write(current);
            if (state == 0 && current == '\n') {
                if (previous == '\r') {
                    state = 1;
                }
            } else if (state == 1 && current == '\n') {
                if (previous == '\r') {
                    break;
                }
                state = 0;
            } else if (current != '\r') {
                state = 0;
            }
            previous = current;
        }

        if (headerBuffer.size() == 0) {
            throw new MalformedHttpRequestException("empty request");
        }

        String headerText = headerBuffer.toString(StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new MalformedHttpRequestException("missing request line");
        }

        String[] requestLineParts = lines[0].trim().split("\\s+");
        if (requestLineParts.length != 3) {
            throw new MalformedHttpRequestException("invalid request line");
        }

        String method = requestLineParts[0];
        String target = requestLineParts[1];
        String version = requestLineParts[2];
        if (!version.startsWith("HTTP/")) {
            throw new MalformedHttpRequestException("invalid HTTP version");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new MalformedHttpRequestException("invalid header");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.put(name, value);
        }

        byte[] body = readBody(input, headers);
        return new HttpRequest(method, target, version, Collections.unmodifiableMap(headers), body);
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers) throws IOException {
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader == null) {
            return new byte[0];
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthHeader);
        } catch (NumberFormatException ex) {
            throw new MalformedHttpRequestException("invalid Content-Length");
        }
        if (contentLength < 0) {
            throw new MalformedHttpRequestException("invalid Content-Length");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new MalformedHttpRequestException("incomplete request body");
        }
        return body;
    }

    public URI originUri() {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return URI.create(target);
        }
        String hostHeader = header("host");
        if (hostHeader == null || hostHeader.isBlank()) {
            throw new MalformedHttpRequestException("missing Host header");
        }
        return URI.create("http://" + hostHeader + normalizePath(target));
    }

    public String originPath() {
        URI uri = originUri();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        return path;
    }

    public String originHost() {
        return originUri().getHost();
    }

    public int originPort() {
        URI uri = originUri();
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizePath(String target) {
        return target.startsWith("/") ? target : "/" + target;
    }
}
