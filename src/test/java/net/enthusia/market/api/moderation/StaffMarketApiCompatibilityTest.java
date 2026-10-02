package net.enthusia.market.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies bean-style value shapes consumed by EnthusiaStaff reflection. */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.TooManyMethods"})
class StaffMarketApiCompatibilityTest {
    /* JUnit 5 intentionally uses its implicit package-private constructor. */
    /* Focused single-assert compatibility tests intentionally exceed */
    /* the configured method-count threshold. */

    /** Shared blacklist timestamp fixture. */
    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void stallIdBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals("stall-1", invoke(ownedStall(), "getId"));
    }

    @Test
    void stallWorldBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals("world", invoke(ownedStall(), "getWorld"));
    }

    @Test
    void stallStateBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals("OWNED", invoke(ownedStall(), "getState"));
    }

    @Test
    void ownershipTypeBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        final Object owner = invoke(ownedStall(), "getOwnership");
        assertEquals(MarketOwnership.Type.SOLO, invoke(owner, "getType"));
    }

    @Test
    void ownershipIdBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        final Object owner = invoke(ownedStall(), "getOwnership");
        assertEquals("owner-1", invoke(owner, "getId"));
    }

    @Test
    void blacklistStatusBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals(StallBlacklistState.Status.ACTIVE, invoke(activeBlacklist(), "getStatus"));
    }

    @Test
    void blacklistExpirationBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals(EXPIRY, invoke(activeBlacklist(), "getExpiresAt"));
    }

    @Test
    void blacklistCaseBeanAliasMatchesStaffShape() throws ReflectiveOperationException {
        assertEquals("ES-CASE-1", invoke(activeBlacklist(), "getCaseId"));
    }

    @Test
    void recordStyleOwnershipAccessorRemainsOptional() {
        final MarketOwnership ownership = ownedStall().ownership();
        assertEquals(Optional.of("owner-1"), ownership.id());
    }

    @Test
    void recordStyleBlacklistExpirationRemainsOptional() {
        assertEquals(Optional.of(EXPIRY), activeBlacklist().expiresAt());
    }

    @Test
    void unownedBeanAliasReturnsNullIdentity() {
        final MarketOwnership ownership = new MarketOwnership(MarketOwnership.Type.NONE, Optional.empty());
        assertNull(ownership.getId());
    }

    @Test
    void removedBlacklistBeanAliasReturnsNullExpiration() {
        assertNull(removedBlacklist().getExpiresAt());
    }

    private static MarketStallRecord ownedStall() {
        final MarketOwnership ownership = new MarketOwnership(
                MarketOwnership.Type.SOLO,
                Optional.of("owner-1")
        );
        return new MarketStallRecord(
                "stall-1",
                "world",
                "OWNED",
                ownership,
                3L,
                false,
                Optional.empty()
        );
    }

    private static StallBlacklistState activeBlacklist() {
        return new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.ACTIVE,
                Optional.of(EXPIRY),
                "ES-CASE-1",
                UUID.randomUUID(),
                1L,
                EXPIRY.minusSeconds(60L)
        );
    }

    private static StallBlacklistState removedBlacklist() {
        return new StallBlacklistState(
                UUID.randomUUID(),
                StallBlacklistState.Status.REMOVED,
                Optional.empty(),
                "ES-CASE-2",
                UUID.randomUUID(),
                1L,
                EXPIRY
        );
    }

    private static Object invoke(final Object target, final String methodName)
            throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
