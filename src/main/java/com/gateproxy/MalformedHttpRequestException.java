package com.gateproxy;

public class MalformedHttpRequestException extends RuntimeException {
    public MalformedHttpRequestException(String message) {
        super(message);
    }
}
