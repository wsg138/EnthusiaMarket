package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketStallCorrectionPreparedSelectionTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000074");
    private static final Instant PREPARED_AT = Instant.parse("2026-10-08T22:00:00Z");

    private static MarketStallRecord stall(String id, UUID owner, String state,
                                           long revision, boolean locked) {
        return new MarketStallRecord(id, "world", state,
                new MarketOwnership(MarketOwnership.Type.SOLO, Optional.of(owner.toString())),
                revision, locked, Optional.empty());
    }

    private static MarketStallCorrectionPreparedSelection.Request request(long caseRevision) {
        return new MarketStallCorrectionPreparedSelection.Request(
                OPERATION, REVIEWER, SUBJECT, "ES-CASE-472", caseRevision,
                "surplus", "world", 8L, PREPARED_AT);
    }

    @Test
    void exactRecordCanBeRevalidatedWithoutSelectingAnotherStall() {
        var lawful = stall("lawful", SUBJECT, "OWNED", 3L, false);
        var surplus = stall("surplus", SUBJECT, "OWNED", 8L, false);
        var prepared = MarketStallCorrectionPreparedSelection.prepare(
                request(2L), List.of(lawful, surplus));

        assertEquals(OPERATION, prepared.request().operationId());
        assertEquals(REVIEWER, prepared.request().reviewerId());
        assertEquals(2L, prepared.request().caseRevision());
        assertEquals(surplus, prepared.observed());
        assertEquals(surplus, prepared.revalidate(List.of(surplus, lawful)));
        // Unrelated owner's normal updates do not change the selected exact stall.
        assertEquals(surplus, prepared.revalidate(List.of(
                stall("lawful", SUBJECT, "OWNED", 4L, false), surplus)));
    }

    @Test
    void sameRevisionStateDriftIsRejectedEvenWhenBasicPreflightWouldAcceptIt() {
        var original = stall("surplus", SUBJECT, "OWNED", 8L, false);
        var prepared = MarketStallCorrectionPreparedSelection.prepare(
                request(2L), List.of(original));
        var graceWithoutRevisionIncrease = stall("surplus", SUBJECT, "GRACE", 8L, false);

        assertEquals(graceWithoutRevisionIncrease, MarketStallCorrectionPreflight.select(
                OPERATION, SUBJECT, "ES-CASE-472", "surplus", "world", 8L,
                List.of(graceWithoutRevisionIncrease)).stall());
        assertThrows(IllegalStateException.class,
                () -> prepared.revalidate(List.of(graceWithoutRevisionIncrease)));
    }

    @Test
    void refusesChangedRevisionOwnershipOrModerationLockBeforeMutation() {
        var prepared = MarketStallCorrectionPreparedSelection.prepare(
                request(3L), List.of(stall("surplus", SUBJECT, "OWNED", 8L, false)));

        assertThrows(IllegalStateException.class, () ->
                prepared.revalidate(List.of(stall("surplus", SUBJECT, "OWNED", 9L, false))));
        assertThrows(IllegalArgumentException.class, () ->
                prepared.revalidate(List.of(stall("surplus", OTHER, "OWNED", 8L, false))));
        assertThrows(IllegalStateException.class, () ->
                prepared.revalidate(List.of(stall("surplus", SUBJECT, "OWNED", 8L, true))));
        assertThrows(IllegalStateException.class, () -> prepared.revalidate(List.of()));
    }

    @Test
    void caseRevisionMustBePresentAndPositiveButIsNotAuthorization() {
        assertThrows(IllegalArgumentException.class, () -> request(0L));
        assertThrows(IllegalArgumentException.class, () -> request(-1L));
        var prepared = MarketStallCorrectionPreparedSelection.prepare(request(10L),
                List.of(stall("surplus", SUBJECT, "OWNED", 8L, false)));
        assertEquals(10L, prepared.request().caseRevision());
        assertEquals("ES-CASE-472", prepared.request().caseId());
    }

    @Test
    void cannotPrepareAmbiguousProviderInventory() {
        var duplicate = stall("surplus", SUBJECT, "OWNED", 8L, false);
        assertThrows(IllegalStateException.class, () ->
                MarketStallCorrectionPreparedSelection.prepare(request(1L),
                        List.of(duplicate, duplicate)));
    }
}
