package net.enthusia.market.api.moderation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only validation for selecting one precisely identified player-owned stall.
 *
 * <p>This is a candidate for a future provider-owned, case-authorized correction,
 * not a deletion endpoint or a completion receipt. A mutating provider must repeat
 * ownership and revision checks inside its own durable transaction.</p>
 */
public final class MarketStallCorrectionPreflight {
    private MarketStallCorrectionPreflight() {
    }

    /**
     * Selects the exact stall ID and revision; never guesses which of multiple
     * stalls is the surplus one.
     *
     * @throws IllegalArgumentException for malformed intent or foreign owner
     * @throws IllegalStateException for missing, stale, locked or ambiguous provider state
     */
    public static Selection select(
            UUID operationId,
            UUID subjectId,
            String caseId,
            String stallId,
            String expectedWorld,
            long expectedRevision,
            List<MarketStallRecord> currentStalls
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(subjectId, "subjectId");
        MarketApiValidation.identifier(caseId, "case id", 64);
        MarketApiValidation.identifier(stallId, "stall id", 128);
        MarketApiValidation.identifier(expectedWorld, "expected world", 128);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expected stall revision cannot be negative");
        }
        Objects.requireNonNull(currentStalls, "currentStalls");

        MarketStallRecord selected = locateExactOwnedStall(subjectId, stallId, currentStalls);
        if (!expectedWorld.equals(selected.world()) || expectedRevision != selected.revision()) {
            throw new IllegalStateException("Selected stall changed since the observed state");
        }
        if (!"OWNED".equals(selected.state()) && !"GRACE".equals(selected.state())) {
            throw new IllegalStateException("Selected stall is not in a player-owned market state");
        }
        if (selected.moderationLocked() || selected.reviewDueAt().isPresent()) {
            throw new IllegalStateException("Selected stall is reserved by another moderation operation");
        }
        return new Selection(operationId, subjectId, caseId, selected);
    }

    private static MarketStallRecord locateExactOwnedStall(
            UUID subjectId,
            String stallId,
            List<MarketStallRecord> currentStalls
    ) {
        Set<String> seenIds = new HashSet<>();
        MarketStallRecord selected = null;
        for (MarketStallRecord stall : currentStalls) {
            if (stall == null || !seenIds.add(stall.id())) {
                throw new IllegalStateException("Provider reported ambiguous market stall records");
            }
            if (stall.ownership().type() != MarketOwnership.Type.SOLO
                    || !stall.ownership().id().orElse("").equals(subjectId.toString())) {
                throw new IllegalArgumentException("Market stall list is not owned by the stated player");
            }
            if (stall.id().equals(stallId)) {
                selected = stall;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("Exact named stall was not found for player");
        }
        return selected;
    }

    /** Immutable observed candidate; never a verified, durable mutation receipt. */
    public static final class Selection {
        private final UUID operationId;
        private final UUID subjectId;
        private final String caseId;
        private final MarketStallRecord stall;

        private Selection(UUID operationId, UUID subjectId, String caseId, MarketStallRecord stall) {
            this.operationId = operationId;
            this.subjectId = subjectId;
            this.caseId = caseId;
            this.stall = stall;
        }

        public UUID operationId() {
            return operationId;
        }

        public UUID subjectId() {
            return subjectId;
        }

        public String caseId() {
            return caseId;
        }

        public MarketStallRecord stall() {
            return stall;
        }
    }
}
