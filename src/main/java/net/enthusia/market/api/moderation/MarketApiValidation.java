package net.enthusia.market.api.moderation;

import java.util.Locale;
import java.util.Objects;

final class MarketApiValidation {
    private MarketApiValidation() {
    }

    static String identifier(String value, String field, int maximumLength) {
        String checked = text(value, field, maximumLength);
        if (!checked.equals(checked.trim()) || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return checked;
    }

    static String checksum(String value, String field) {
        String checked = text(value, field, 64).toLowerCase(Locale.ROOT);
        if (checked.length() != 64 || !checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 checksum");
        }
        return checked;
    }

    static String text(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or exceeds " + maximumLength);
        }
        return value;
    }
}
