package net.badgersmc.em.websync

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.RentTimingPolicy
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Bukkit

class PublicSnapshotProjector(
    private val stalls: StallRepository,
    private val shops: ShopRepository,
    private val regions: RegionProvider,
    private val guilds: GuildProvider,
    private val availability: ShopAvailabilityCalculator,
    private val canonical: CanonicalMarketMap,
    private val config: EnthusiaMarketConfig,
    private val items: PublicItemSerializer = PublicItemSerializer(),
    avatars: PublicOwnerAvatarResolver = PublicOwnerAvatarResolver(),
    private val ownerProjection: PublicOwnerProjection = PublicOwnerProjection(guilds, avatars),
) {
    data class ValidationResult(val errors: List<String>, val diagnostics: List<String>)
    data class Diagnostics(
        var unresolvedOwners: Int = 0,
        var unresolvedMembers: Int = 0,
        var malformedShops: Int = 0,
        var unavailableBuyContainers: Int = 0,
    )

    fun capture(stallId: String, diagnostics: Diagnostics = Diagnostics()): PublicStall {
        check(Bukkit.isPrimaryThread()) { "Public snapshots must be captured on the Bukkit main thread" }
        val mapping = canonical.stalls[stallId] ?: error("missing_canonical_mapping")
        val stall = stalls.findById(StallId(stallId)) ?: error("missing_persisted_stall")
        if (stall.regionId != stallId) error("noncanonical_region_identity")
        val center = projectCenter(stall)
        val publicShops = projectShops(stall, diagnostics)
        val members = projectMembers(stall, diagnostics)
        val owner = projectOwner(stall, diagnostics)
        val effectiveNextRentAt = RentTimingPolicy.effectiveNextRentAt(stall, config)
        return PublicStall(
            id = stallId,
            buildingId = mapping.buildingId,
            floor = mapping.floor,
            location = center,
            owner = owner,
            stallState = stall.state.name,
            ownerSince = stall.ownerSince?.toString(),
            nextRentAt = effectiveNextRentAt?.toString(),
            graceEndsAt = RentTimingPolicy.graceEndsAt(stall, config)?.toString(),
            rentTimingStatus = rentTimingStatus(stall, effectiveNextRentAt != null),
            members = members,
            shops = publicShops,
        )
    }

    private fun projectCenter(stall: Stall): PublicLocation {
        val bounds = regions.bounds(stall.world, stall.regionId) ?: error("missing_region")
        if (invalid(bounds)) error("invalid_bounds")
        return PublicLocation(
            stall.world,
            centerCoordinate(bounds.minX, bounds.maxX),
            centerCoordinate(bounds.minY, bounds.maxY),
            centerCoordinate(bounds.minZ, bounds.maxZ),
        )
    }

    private fun centerCoordinate(minimum: Int, maximum: Int): Int {
        val value = Math.floorDiv(minimum.toLong() + maximum.toLong(), 2L)
        if (value !in -30_000_000L..30_000_000L) error("invalid_bounds")
        return value.toInt()
    }

    private fun projectShops(stall: Stall, diagnostics: Diagnostics): List<PublicShop> {
        val persistedShops = shops.findByStall(stall.id.value)
        if (persistedShops.size > 256) error("shop_count_limit")
        return persistedShops.sortedBy { it.id }.mapNotNull { projectShop(it, stall, diagnostics) }
    }

    private fun projectShop(shop: Shop, stall: Stall, diagnostics: Diagnostics): PublicShop? {
        if (shop.id <= 0 || shop.id > Int.MAX_VALUE) {
            diagnostics.malformedShops++
            return null
        }
        return runCatching { buildPublicShop(shop, stall, diagnostics) }
            .getOrElse {
                diagnostics.malformedShops++
                null
            }
    }

    private fun buildPublicShop(shop: Shop, stall: Stall, diagnostics: Diagnostics): PublicShop {
        val sell = ItemStackSerializer.deserialize(shop.sellItem) ?: error("sell_item")
        val cost = ItemStackSerializer.deserialize(shop.costItem) ?: error("cost_item")
        val resolvedShopName = Bukkit.getOfflinePlayer(shop.owner).name
        if (resolvedShopName == null) diagnostics.unresolvedOwners++
        val shopName = PublicNameResolver.player(shop.owner) { resolvedShopName }
        val available = availability.availableTrades(shop, stall)
        if (shop.direction.name == "BUY" && available == 0) diagnostics.unavailableBuyContainers++
        return PublicShop(
            id = shop.id,
            owner = PublicIdentity(shop.owner.toString(), shopName.take(64)),
            direction = shop.direction.name,
            sellItem = items.serialize(sell),
            sellAmount = shop.sellAmount,
            costItem = items.serialize(cost),
            costAmount = shop.costAmount,
            interaction = PublicInteraction(shop.signWorld, shop.signX, shop.signY, shop.signZ, "SHOP_SIGN"),
            stockCount = shop.stockCount,
            availableTrades = available,
            searchable = shop.searchEnabled,
        )
    }

    private fun projectMembers(stall: Stall, diagnostics: Diagnostics): List<String> {
        if (stall.members.size > 256) error("member_count_limit")
        return stall.members.mapNotNull { member ->
            PublicNameResolver.delegatedMember(member) { Bukkit.getOfflinePlayer(it).name }
                ?.take(64) ?: unresolvedMember(diagnostics)
        }.distinct().sorted().take(256)
    }

    private fun unresolvedMember(diagnostics: Diagnostics): String? {
        diagnostics.unresolvedMembers++
        return null
    }

    private fun projectOwner(stall: Stall, diagnostics: Diagnostics): PublicOwner {
        val projectedOwner = ownerProjection.project(stall.owner)
        if (projectedOwner.unresolved) diagnostics.unresolvedOwners++
        return projectedOwner.owner
    }

    private fun rentTimingStatus(stall: Stall, hasEffectiveNextRent: Boolean): String {
        return when {
            stall.state != StallState.OWNED && stall.state != StallState.GRACE -> "NOT_APPLICABLE"
            stall.nextRentAt != null -> "PERSISTED"
            hasEffectiveNextRent -> "LEGACY_DERIVED"
            else -> "UNAVAILABLE"
        }
    }

    fun validateLive(): List<String> {
        return validateLiveReport().errors
    }

    fun validateLiveReport(): ValidationResult {
        val errors = canonical.validate().toMutableList()
        val diagnostics = mutableListOf("canonical_duplicate_visible_geometry:stall60,stall62")
        val expected = canonical.stallIds.toSet()
        val persisted = stalls.all().map { it.id.value }.toSet()
        if (persisted != expected) errors += "persisted_stall_ids"
        canonical.stallIds.forEach { validateLiveStall(it, errors) }
        diagnostics += liveBoundsDiagnostic()
        return ValidationResult(errors, diagnostics)
    }

    private fun validateLiveStall(id: String, errors: MutableList<String>) {
        val stall = stalls.findById(StallId(id))
        if (stall == null) {
            errors += "missing_stall:$id"
            return
        }
        if (stall.regionId != id || !regions.exists(stall.world, id)) errors += "missing_region:$id"
        val bounds = regions.bounds(stall.world, id)
        when {
            bounds == null || invalid(bounds) -> errors += "invalid_bounds:$id"
            !validCenter(bounds) -> errors += "invalid_center:$id"
        }
    }

    private fun liveBoundsDiagnostic(): String {
        val sixty = stalls.findById(StallId("stall60"))?.let { regions.bounds(it.world, it.regionId) }
        val sixtyTwo = stalls.findById(StallId("stall62"))?.let { regions.bounds(it.world, it.regionId) }
        return when {
            sixty == null || sixtyTwo == null -> "stall60_stall62_live_bounds:unavailable"
            sixty == sixtyTwo -> "stall60_stall62_live_bounds:same"
            else -> "stall60_stall62_live_bounds:different"
        }
    }

    private fun invalid(bounds: RegionProvider.RegionBounds): Boolean {
        return bounds.minX > bounds.maxX || bounds.minY > bounds.maxY || bounds.minZ > bounds.maxZ
    }

    private fun validCenter(bounds: RegionProvider.RegionBounds): Boolean {
        return listOf(
            Math.floorDiv(bounds.minX.toLong() + bounds.maxX.toLong(), 2L),
            Math.floorDiv(bounds.minY.toLong() + bounds.maxY.toLong(), 2L),
            Math.floorDiv(bounds.minZ.toLong() + bounds.maxZ.toLong(), 2L),
        ).all { it in -30_000_000L..30_000_000L }
    }
}
