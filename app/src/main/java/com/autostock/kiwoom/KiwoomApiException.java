package com.autostock.kiwoom;

public class KiwoomApiException extends RuntimeException {

    public KiwoomApiException(String message) {
        super(message);
    }

    public KiwoomApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
