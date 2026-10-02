package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies moderation API record validation and time-bound behavior. */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.TooManyMethods"})
class MarketModerationContractTest {
    /* JUnit 5 intentionally uses its implicit package-private constructor. */
    /* Focused single-assert tests intentionally exceed PMD's method-count threshold. */

    /** Stable SHA-256-shaped fixture used by destructive operation records. */
    private static final String CHECKSUM = "a".repeat(64);
    /** Shared moderation review instant. */
    private static final Instant REVIEW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void unownedOwnershipAcceptsNoIdentity() {
        assertTrue(new MarketOwnership(MarketOwnership.Type.NONE, Optional.empty()).id().isEmpty());
    }

    @Test
    void ownedOwnershipRequiresIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOwnership(MarketOwnership.Type.SOLO, Optional.empty())
        );
    }

    @Test
    void unownedOwnershipRejectsIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOwnership(MarketOwnership.Type.NONE, Optional.of("unexpected"))
        );
    }

    @Test
    void operationRequestBoundsRecoveryWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOperationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ES-CASE-1",
                        "stall-1",
                        REVIEW,
                        REVIEW.plusSeconds(32L * 86_400L),
                        Optional.empty()
                )
        );
    }

    @Test
    void identifiersRejectAsciiWhitespace() {
        assertInvalidIdentifier("CASE 1", REVIEW);
    }

    @Test
    void identifiersRejectUnicodeWhitespace() {
        assertInvalidIdentifier("CASE\u20071", REVIEW);
    }

    @Test
    void blacklistIsActiveBeforeExpiration() {
        final StallBlacklistState state = activeBlacklist(REVIEW);
        assertTrue(state.activeAt(REVIEW.minusNanos(1L)));
    }

    @Test
    void blacklistIsInactiveAtExpiration() {
        final StallBlacklistState state = activeBlacklist(REVIEW);
        assertFalse(state.activeAt(REVIEW));
    }

    @Test
    void confiscationRequiresFullChecksum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketConfiscationApproval(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "short",
                        Instant.now()
                )
        );
    }

    @Test
    void restoreRetainsExpectedChecksum() {
        final MarketRestoreRequest request = new MarketRestoreRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CHECKSUM
        );
        assertEquals(CHECKSUM, request.expectedCurrentChecksum());
    }

    private static StallBlacklistState activeBlacklist(final Instant expiry) {
        return new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.ACTIVE,
                Optional.of(expiry),
                "ES-CASE-1",
                UUID.randomUUID(),
                1L,
                expiry.minusSeconds(60L)
        );
    }

    private static void assertInvalidIdentifier(final String caseId, final Instant review) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOperationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        caseId,
                        "stall-1",
                        review,
                        review.plusSeconds(86_400L),
                        Optional.empty()
                )
        );
    }
}
