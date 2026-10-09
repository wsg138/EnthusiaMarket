package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MarketStallCorrectionPreflightTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000043");

    private static MarketStallRecord stall(String id, UUID owner, long revision, boolean locked) {
        return new MarketStallRecord(
                id, "world", "OWNED",
                new MarketOwnership(MarketOwnership.Type.SOLO, Optional.of(owner.toString())),
                revision, locked, Optional.empty()
        );
    }

    private static MarketStallCorrectionPreflight.Selection select(
            String target, long revision, List<MarketStallRecord> current
    ) {
        return MarketStallCorrectionPreflight.select(
                OPERATION, SUBJECT, "ES-CASE-1", target, "world", revision, current
        );
    }

    @Test
    void choosesExactlyOneRequestedStallNotTheFirstOrAnArbitraryExtra() {
        MarketStallRecord lawful = stall("lawful", SUBJECT, 7L, false);
        MarketStallRecord surplus = stall("surplus", SUBJECT, 12L, false);
        var selection = select("surplus", 12L, List.of(lawful, surplus));
        assertEquals(OPERATION, selection.operationId());
        assertEquals(SUBJECT, selection.subjectId());
        assertEquals("ES-CASE-1", selection.caseId());
        assertEquals(surplus, selection.stall());
    }

    @Test
    void rejectsWrongPlayerOrUnrelatedOwnership() {
        assertThrows(IllegalArgumentException.class, () ->
                select("surplus", 1L, List.of(stall("surplus", OTHER, 1L, false))));
        MarketStallRecord guild = new MarketStallRecord(
                "surplus", "world", "OWNED",
                new MarketOwnership(MarketOwnership.Type.GUILD, Optional.of("guild-1")),
                1L, false, Optional.empty()
        );
        assertThrows(IllegalArgumentException.class, () -> select("surplus", 1L, List.of(guild)));
        assertThrows(IllegalArgumentException.class, () ->
                select("surplus", 1L, List.of(stall("surplus", SUBJECT, 1L, false),
                        stall("foreign", OTHER, 1L, false))));
    }

    @Test
    void refusesMissingDuplicateStaleAndLockedStall() {
        MarketStallRecord current = stall("surplus", SUBJECT, 4L, false);
        assertThrows(IllegalStateException.class, () -> select("wrong", 4L, List.of(current)));
        assertThrows(IllegalStateException.class, () -> select("surplus", 4L, List.of(current, current)));
        assertThrows(IllegalStateException.class, () -> select("surplus", 3L, List.of(current)));
        assertThrows(IllegalStateException.class, () ->
                select("surplus", 4L, List.of(stall("surplus", SUBJECT, 4L, true))));
        assertThrows(IllegalStateException.class, () -> select("surplus", 4L, List.of()));
    }

    @Test
    void refusesHeldOrTransferringStallsEvenIfLockFlagIsMissing() {
        for (String state : List.of("MODERATION_HOLD", "RE_AUCTIONING", "UNOWNED", "AUCTIONING")) {
            MarketStallRecord stalled = new MarketStallRecord(
                    "surplus", "world", state,
                    new MarketOwnership(MarketOwnership.Type.SOLO, Optional.of(SUBJECT.toString())),
                    4L, false, Optional.empty()
            );
            assertThrows(IllegalStateException.class, () -> select("surplus", 4L, List.of(stalled)));
        }
        MarketStallRecord pendingReview = new MarketStallRecord(
                "surplus", "world", "OWNED",
                new MarketOwnership(MarketOwnership.Type.SOLO, Optional.of(SUBJECT.toString())),
                4L, false, Optional.of(Instant.parse("2026-10-08T17:00:00Z"))
        );
        assertThrows(IllegalStateException.class, () -> select("surplus", 4L, List.of(pendingReview)));
        MarketStallRecord grace = new MarketStallRecord(
                "surplus", "world", "GRACE",
                new MarketOwnership(MarketOwnership.Type.SOLO, Optional.of(SUBJECT.toString())),
                4L, false, Optional.empty()
        );
        assertEquals(grace, select("surplus", 4L, List.of(grace)).stall());
    }

    @Test
    void refusesOversizedProviderSnapshotInsteadOfSelectingFromIncompleteInventory() {
        // The provider's findStalls() refuses any inventory above 100.
        // Standalone preflight must not silently accept a fabricated 101-row list.
        List<MarketStallRecord> impossible = IntStream.range(0, 101)
                .mapToObj(index -> stall("s-" + index, SUBJECT, 1L, false))
                .toList();
        assertThrows(IllegalStateException.class, () -> select("s-0", 1L, impossible));
        // A complete, correctly bounded 100-row list can still name one exact stall.
        assertEquals("s-99", select("s-99", 1L, impossible.subList(0, 100)).stall().id());
    }

    @Test
    void refusesStaleWorldAndMalformedIntent() {
        MarketStallRecord current = stall("surplus", SUBJECT, 4L, false);
        assertThrows(IllegalStateException.class, () -> MarketStallCorrectionPreflight.select(
                OPERATION, SUBJECT, "ES-CASE-1", "surplus", "nether", 4L, List.of(current)));
        assertThrows(IllegalArgumentException.class, () -> select("surplus", -1L, List.of(current)));
        assertThrows(IllegalArgumentException.class, () -> MarketStallCorrectionPreflight.select(
                OPERATION, SUBJECT, "CASE HAS SPACE", "surplus", "world", 4L, List.of(current)));
        assertThrows(NullPointerException.class, () -> select("surplus", 4L, null));
    }
}
