package net.badgersmc.em.websync

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import org.bukkit.Bukkit
import java.util.UUID

class PublicSnapshotProjector(
    private val stalls: StallRepository,
    private val shops: ShopRepository,
    private val regions: RegionProvider,
    private val guilds: GuildProvider,
    private val availability: ShopAvailabilityCalculator,
    private val canonical: CanonicalMarketMap,
    private val items: PublicItemSerializer = PublicItemSerializer(),
) {
    data class ValidationResult(val errors: List<String>, val diagnostics: List<String>)
    data class Diagnostics(
        var unresolvedOwners: Int = 0,
        var unresolvedMembers: Int = 0,
        var malformedShops: Int = 0,
        var unavailableBuyContainers: Int = 0,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun capture(stallId: String, diagnostics: Diagnostics = Diagnostics()): PublicStall {
        check(Bukkit.isPrimaryThread()) { "Public snapshots must be captured on the Bukkit main thread" }
        val mapping = canonical.stalls[stallId] ?: error("missing_canonical_mapping")
        val stall = stalls.findById(StallId(stallId)) ?: error("missing_persisted_stall")
        if (stall.regionId != stallId) error("noncanonical_region_identity")
        val bounds = regions.bounds(stall.world, stall.regionId) ?: error("missing_region")
        if (bounds.minX > bounds.maxX || bounds.minY > bounds.maxY || bounds.minZ > bounds.maxZ) error("invalid_bounds")
        fun center(minimum: Int, maximum: Int): Int {
            val value = Math.floorDiv(minimum.toLong() + maximum.toLong(), 2L)
            if (value !in -30_000_000L..30_000_000L) error("invalid_bounds")
            return value.toInt()
        }
        val center = PublicLocation(
            stall.world,
            center(bounds.minX, bounds.maxX),
            center(bounds.minY, bounds.maxY),
            center(bounds.minZ, bounds.maxZ),
        )
        val persistedShops = shops.findByStall(stallId)
        if (persistedShops.size > 256) error("shop_count_limit")
        val publicShops = persistedShops.sortedBy { it.id }.mapNotNull { shop ->
            if (shop.id <= 0 || shop.id > Int.MAX_VALUE) { diagnostics.malformedShops++; return@mapNotNull null }
            runCatching {
                val sell = ItemStackSerializer.deserialize(shop.sellItem) ?: error("sell_item")
                val cost = ItemStackSerializer.deserialize(shop.costItem) ?: error("cost_item")
                val resolvedShopName = Bukkit.getOfflinePlayer(shop.owner).name
                if (resolvedShopName == null) diagnostics.unresolvedOwners++
                val shopName = PublicNameResolver.player(shop.owner) { resolvedShopName }
                val available = availability.availableTrades(shop, stall)
                if (shop.direction.name == "BUY" && available == 0) diagnostics.unavailableBuyContainers++
                PublicShop(
                    id = shop.id,
                    owner = PublicIdentity(shop.owner.toString(), shopName.take(64)),
                    direction = shop.direction.name,
                    sellItem = items.serialize(sell),
                    costItem = items.serialize(cost),
                    interaction = PublicInteraction(shop.signWorld, shop.signX, shop.signY, shop.signZ, "SHOP_SIGN"),
                    stockCount = shop.stockCount,
                    availableTrades = available,
                    searchable = shop.searchEnabled,
                )
            }.getOrElse { diagnostics.malformedShops++; null }
        }
        if (stall.members.size > 256) error("member_count_limit")
        val members = stall.members.mapNotNull { member ->
            PublicNameResolver.delegatedMember(member) { Bukkit.getOfflinePlayer(it).name }
                ?.take(64) ?: run { diagnostics.unresolvedMembers++; null }
        }.distinct().sorted().take(256)
        val owner = when (stall.owner.type) {
            OwnerType.NONE -> PublicOwner("NONE", null, null, "Unowned", avatar = PublicAvatar("NONE"))
            OwnerType.SOLO -> {
                val uuid = runCatching { UUID.fromString(stall.owner.id) }.getOrNull()
                val resolved = uuid?.let { Bukkit.getOfflinePlayer(it).name }
                if (resolved == null) diagnostics.unresolvedOwners++
                val name = if (uuid == null) "Unknown Player" else PublicNameResolver.player(uuid) { resolved }
                PublicOwner("PLAYER", stall.owner.id, uuid?.toString(), name.take(64),
                    avatar = PublicAvatar("MINECRAFT_HEAD", "JAVA", true))
            }
            OwnerType.GUILD -> {
                val guild = guilds.guildById(stall.owner.id)
                if (guild == null) diagnostics.unresolvedOwners++
                PublicOwner("GUILD", stall.owner.id, null,
                    PublicNameResolver.guild(stall.owner.id) { guild?.name }.take(64),
                    avatar = PublicAvatar("GUILD"))
            }
        }
        return PublicStall(
            id = stallId,
            buildingId = mapping.buildingId,
            floor = mapping.floor,
            location = center,
            owner = owner,
            ownerSince = stall.ownerSince?.toString(),
            nextRentAt = stall.nextRentAt?.toString(),
            members = members,
            shops = publicShops,
        )
    }

    fun validateLive(): List<String> = validateLiveReport().errors

    @Suppress("CyclomaticComplexMethod")
    fun validateLiveReport(): ValidationResult {
        val errors = canonical.validate().toMutableList()
        val diagnostics = mutableListOf("canonical_duplicate_visible_geometry:stall60,stall62")
        val expected = canonical.stallIds.toSet()
        val persisted = stalls.all().map { it.id.value }.toSet()
        if (persisted != expected) errors += "persisted_stall_ids"
        for (id in canonical.stallIds) {
            val stall = stalls.findById(StallId(id))
            if (stall == null) { errors += "missing_stall:$id"; continue }
            if (stall.regionId != id || !regions.exists(stall.world, id)) errors += "missing_region:$id"
            val bounds = regions.bounds(stall.world, id)
            if (bounds == null || invalid(bounds)) {
                errors += "invalid_bounds:$id"
            } else if (!validCenter(bounds)) {
                errors += "invalid_center:$id"
            }
        }
        val sixty = stalls.findById(StallId("stall60"))?.let { regions.bounds(it.world, it.regionId) }
        val sixtyTwo = stalls.findById(StallId("stall62"))?.let { regions.bounds(it.world, it.regionId) }
        diagnostics += when {
            sixty == null || sixtyTwo == null -> "stall60_stall62_live_bounds:unavailable"
            sixty == sixtyTwo -> "stall60_stall62_live_bounds:same"
            else -> "stall60_stall62_live_bounds:different"
        }
        return ValidationResult(errors, diagnostics)
    }

    private fun invalid(bounds: RegionProvider.RegionBounds): Boolean =
        bounds.minX > bounds.maxX || bounds.minY > bounds.maxY || bounds.minZ > bounds.maxZ

    private fun validCenter(bounds: RegionProvider.RegionBounds): Boolean = listOf(
        Math.floorDiv(bounds.minX.toLong() + bounds.maxX.toLong(), 2L),
        Math.floorDiv(bounds.minY.toLong() + bounds.maxY.toLong(), 2L),
        Math.floorDiv(bounds.minZ.toLong() + bounds.maxZ.toLong(), 2L),
    ).all { it in -30_000_000L..30_000_000L }
}
