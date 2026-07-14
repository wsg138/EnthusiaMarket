package net.badgersmc.em.websync

data class PublicLocation(val world: String, val x: Int, val y: Int, val z: Int)
data class PublicAvatar(val kind: String, val source: String? = null, val includesOuterLayer: Boolean? = null)
data class PublicOwner(
    val type: String,
    val id: String?,
    val uuid: String?,
    val name: String,
    val avatarUrl: String? = null,
    val avatar: PublicAvatar,
)
data class PublicIdentity(val id: String, val name: String)
data class PublicInteraction(val world: String, val x: Int, val y: Int, val z: Int, val source: String)
data class PublicEnchantment(val id: String, val displayName: String, val level: Int)
data class PublicPotionEffect(val name: String, val amplifier: Int, val durationSeconds: Int)
data class PublicPotion(
    val id: String,
    val basePotion: String,
    val form: String,
    val color: String,
    val effects: List<PublicPotionEffect>,
)
data class PublicArmorTrim(val pattern: String, val material: String)
data class PublicSmithingTemplate(val type: String)
data class PublicWrittenBook(val title: String, val author: String, val generation: String, val pageCount: Int)
data class PublicContainerEntry(val slot: Int? = null, val item: PublicItem)
data class PublicContainer(
    val type: String,
    val slots: Int? = null,
    val capacityUsed: Int? = null,
    val capacityMax: Int? = null,
    val contents: List<PublicContainerEntry>,
)
data class PublicItemMetadata(
    val customName: String? = null,
    val enchantments: List<PublicEnchantment>? = null,
    val storedEnchantments: List<PublicEnchantment>? = null,
    val potion: PublicPotion? = null,
    val armorTrim: PublicArmorTrim? = null,
    val smithingTemplate: PublicSmithingTemplate? = null,
    val writtenBook: PublicWrittenBook? = null,
    val shulkerColor: String? = null,
    val container: PublicContainer? = null,
)
data class PublicItem(
    val material: String,
    val displayName: String,
    val amount: Int,
    val icon: String? = null,
    val metadata: PublicItemMetadata,
)
data class PublicShop(
    val id: Long,
    val owner: PublicIdentity,
    val direction: String,
    val sellItem: PublicItem,
    val sellAmount: Int,
    val costItem: PublicItem,
    val costAmount: Int,
    val interaction: PublicInteraction,
    val stockCount: Int,
    val availableTrades: Int,
    val searchable: Boolean,
)
data class PublicStall(
    val id: String,
    val buildingId: String,
    val floor: Int,
    val location: PublicLocation,
    val owner: PublicOwner,
    val ownerSince: String?,
    val nextRentAt: String?,
    val members: List<String>,
    val shops: List<PublicShop>,
)
data class RevisionedStall(val revision: Long, val stall: PublicStall)
data class StallUpdateRequest(
    val schemaVersion: Int = 1,
    val serverId: String = "enthusia-main",
    val serverEpoch: String,
    val eventId: String,
    val sentAt: String,
    val revision: Long,
    val stall: PublicStall,
)
data class FullSyncRequest(
    val schemaVersion: Int = 1,
    val serverId: String = "enthusia-main",
    val serverEpoch: String,
    val eventId: String,
    val sentAt: String,
    val snapshotRevision: Long,
    val generatedAt: String,
    val stalls: List<RevisionedStall>,
)
data class TestRequest(
    val schemaVersion: Int = 1,
    val serverId: String = "enthusia-main",
    val serverEpoch: String,
    val eventId: String,
    val sentAt: String,
    val probe: String,
)
