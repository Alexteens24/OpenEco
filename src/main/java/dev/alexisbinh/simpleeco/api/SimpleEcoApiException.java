package dev.alexisbinh.simpleeco.api;

public class SimpleEcoApiException extends RuntimeException {

    public SimpleEcoApiException(String message) {
        super(message);
    }

    public SimpleEcoApiException(String message, Throwable cause) {
        super(message, cause);
    }
}