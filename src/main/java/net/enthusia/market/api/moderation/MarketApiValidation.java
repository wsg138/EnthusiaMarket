package net.enthusia.market.api.moderation;

import java.util.Locale;
import java.util.Objects;

/** Shared validation helpers for values crossing the moderation API boundary. */
final class MarketApiValidation {
    private MarketApiValidation() {
    }

    /* package */ static String identifier(final String value, final String field, final int maximumLength) {
        final String checked = text(value, field, maximumLength);
        if (checked.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint)
                        || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return checked;
    }

    /* package */ static String checksum(final String value, final String field) {
        final String checked = text(value, field, 64).toLowerCase(Locale.ROOT);
        if (checked.length() != 64 || !checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 checksum");
        }
        return checked;
    }

    /* package */ static String text(final String value, final String field, final int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or exceeds " + maximumLength);
        }
        return value;
    }
}
