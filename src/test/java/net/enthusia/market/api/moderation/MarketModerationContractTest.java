package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketModerationContractTest {
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void ownershipRequiresIdentityOnlyWhenOwned() {
        assertTrue(new MarketOwnership(MarketOwnership.Type.NONE, Optional.empty()).id().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOwnership(MarketOwnership.Type.SOLO, Optional.empty())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOwnership(MarketOwnership.Type.NONE, Optional.of("unexpected"))
        );
    }

    @Test
    void operationRequestBoundsRecoveryWindow() {
        Instant review = Instant.parse("2026-08-20T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketOperationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ES-CASE-1",
                        "stall-1",
                        review,
                        review.plusSeconds(32L * 86_400L),
                        Optional.empty()
                )
        );
    }

    @Test
    void identifiersRejectInternalAndUnicodeWhitespace() {
        Instant review = Instant.parse("2026-08-20T00:00:00Z");
        for (String caseId : new String[]{"CASE 1", "CASE\u20071"}) {
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

    @Test
    void blacklistExpirationIsEvaluatedAtTheProvidedClock() {
        Instant expiry = Instant.parse("2026-08-20T00:00:00Z");
        StallBlacklistState state = new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.ACTIVE,
                Optional.of(expiry),
                "ES-CASE-1",
                UUID.randomUUID(),
                1L,
                expiry.minusSeconds(60L)
        );
        assertTrue(state.activeAt(expiry.minusNanos(1L)));
        assertFalse(state.activeAt(expiry));
    }

    @Test
    void destructiveRequestsRequireFullChecksums() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketConfiscationApproval(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "short",
                        Instant.now()
                )
        );
        assertTrue(new MarketRestoreRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CHECKSUM
        ).expectedCurrentChecksum().equals(CHECKSUM));
    }
}
