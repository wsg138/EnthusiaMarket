package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.Optional;

public record MarketOwnership(Type type, Optional<String> id) {
    public MarketOwnership {
        type = Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id");
        if (type == Type.NONE && id.isPresent()) {
            throw new IllegalArgumentException("unowned market ownership cannot contain an id");
        }
        if (type != Type.NONE) {
            String value = id.orElseThrow(() -> new IllegalArgumentException(
                    "owned market ownership requires an id"
            ));
            MarketApiValidation.identifier(value, "ownership id", 128);
        }
    }

    public enum Type {
        NONE,
        SOLO,
        GUILD
    }
}
