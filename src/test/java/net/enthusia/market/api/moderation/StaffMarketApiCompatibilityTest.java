package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffMarketApiCompatibilityTest {

    @Test
    void beanAccessorsMatchStaffReflectionValueShapes() throws ReflectiveOperationException {
        MarketOwnership ownership = new MarketOwnership(
                MarketOwnership.Type.SOLO,
                Optional.of("owner-1")
        );
        MarketStallRecord stall = new MarketStallRecord(
                "stall-1",
                "world",
                "OWNED",
                ownership,
                3L,
                false,
                Optional.empty()
        );
        Instant expiry = Instant.parse("2026-10-01T00:00:00Z");
        StallBlacklistState blacklist = new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.ACTIVE,
                Optional.of(expiry),
                "ES-CASE-1",
                UUID.randomUUID(),
                1L,
                expiry.minusSeconds(60L)
        );

        assertEquals("stall-1", invoke(stall, "getId"));
        assertEquals("world", invoke(stall, "getWorld"));
        assertEquals("OWNED", invoke(stall, "getState"));
        Object reflectedOwnership = invoke(stall, "getOwnership");
        assertEquals(MarketOwnership.Type.SOLO, invoke(reflectedOwnership, "getType"));
        assertEquals("owner-1", invoke(reflectedOwnership, "getId"));
        assertEquals(StallBlacklistState.Status.ACTIVE, invoke(blacklist, "getStatus"));
        assertEquals(expiry, invoke(blacklist, "getExpiresAt"));
        assertEquals("ES-CASE-1", invoke(blacklist, "getCaseId"));

        assertEquals(Optional.of("owner-1"), ownership.id());
        assertEquals(Optional.of(expiry), blacklist.expiresAt());
    }

    @Test
    void optionalRecordFieldsRemainNullableThroughBeanAliases() {
        MarketOwnership ownership = new MarketOwnership(MarketOwnership.Type.NONE, Optional.empty());
        Instant updated = Instant.parse("2026-10-01T00:00:00Z");
        StallBlacklistState blacklist = new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.REMOVED,
                Optional.empty(),
                "ES-CASE-2",
                UUID.randomUUID(),
                1L,
                updated
        );

        assertNull(ownership.getId());
        assertNull(blacklist.getExpiresAt());
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
