package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

/**
 * Optional metadata for custom history entries written by addons.
 */
public record TransactionMetadata(@Nullable String source, @Nullable String note) {

    public static final int SOURCE_MAX_LENGTH = 64;
    public static final int NOTE_MAX_LENGTH = 255;

    public TransactionMetadata {
        source = normalize(source, "source", SOURCE_MAX_LENGTH);
        note = normalize(note, "note", NOTE_MAX_LENGTH);
    }

    public static TransactionMetadata empty() {
        return new TransactionMetadata(null, null);
    }

    public boolean hasSource() {
        return source != null;
    }

    public boolean hasNote() {
        return note != null;
    }

    public boolean isEmpty() {
        return source == null && note == null;
    }

    private static String normalize(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }
}