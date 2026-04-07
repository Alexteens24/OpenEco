package dev.alexisbinh.openeco.api;

public class OpenEcoApiException extends RuntimeException {

    public OpenEcoApiException(String message) {
        super(message);
    }

    public OpenEcoApiException(String message, Throwable cause) {
        super(message, cause);
    }
}