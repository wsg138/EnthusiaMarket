package net.badgersmc.em.websync

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.block.ShulkerBox
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

class PublicItemSerializer(
    private val maximumDepth: Int = 4,
    private val maximumNodes: Int = 1024,
) {
    private val plain = PlainTextComponentSerializer.plainText()

    fun serialize(root: ItemStack): PublicItem {
        val context = Context(Collections.newSetFromMap(IdentityHashMap()), 0)
        return serialize(root, 0, context)
    }

    private data class Context(val path: MutableSet<ItemStack>, var nodes: Int)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun serialize(item: ItemStack, depth: Int, context: Context): PublicItem {
        require(depth <= maximumDepth) { "Public item nesting exceeds depth limit" }
        require(context.nodes++ < maximumNodes) { "Public item node limit exceeded" }
        require(context.path.add(item)) { "Recursive public item cycle" }
        try {
            val meta = item.itemMeta
            val enchantments = enchantments(meta.enchants)
            val stored = (meta as? EnchantmentStorageMeta)?.let { enchantments(it.storedEnchants) }
            val potion = (meta as? PotionMeta)?.let { potion(it, item.type.name) }
            val trim = (meta as? ArmorMeta)?.trim?.let {
                PublicArmorTrim(it.pattern.key.key, it.material.key.key)
            }
            val written = (meta as? BookMeta)?.let {
                val title = it.title
                val author = it.author
                if (!title.isNullOrBlank() && !author.isNullOrBlank()) {
                    PublicWrittenBook(title.take(256), author.take(128), (it.generation?.name ?: "ORIGINAL").take(64), it.pageCount)
                } else null
            }
            val container = if (depth < maximumDepth) container(item, meta, depth, context) else null
            val material = item.type.name
            val customName = meta.customName()?.let(plain::serialize)?.takeIf { it.isNotBlank() }?.take(256)
            return PublicItem(
                material = material,
                displayName = customName ?: humanName(material),
                amount = item.amount.coerceIn(1, 64_000),
                metadata = PublicItemMetadata(
                    customName = customName,
                    enchantments = enchantments.takeIf { it.isNotEmpty() },
                    storedEnchantments = stored?.takeIf { it.isNotEmpty() },
                    potion = potion,
                    armorTrim = trim,
                    smithingTemplate = material.takeIf { it.endsWith("_SMITHING_TEMPLATE") }
                        ?.let { PublicSmithingTemplate(it) },
                    writtenBook = written,
                    shulkerColor = material.takeIf { it.endsWith("SHULKER_BOX") }
                        ?.removeSuffix("_SHULKER_BOX")?.takeIf { it != "SHULKER" },
                    container = container,
                ),
            )
        } finally {
            context.path.remove(item)
        }
    }

    private fun enchantments(values: Map<Enchantment, Int>): List<PublicEnchantment> =
        values.entries.sortedBy { it.key.key.toString() }.take(128).map { (enchantment, level) ->
            PublicEnchantment(
                enchantment.key.toString().take(128),
                plain.serialize(enchantment.displayName(level)).take(256),
                level.coerceIn(-255, 255),
            )
        }

    private fun potion(meta: PotionMeta, material: String): PublicPotion {
        val base = meta.basePotionType?.name ?: "WATER"
        val effects = meta.customEffects.sortedWith(compareBy({ it.type.key.toString() }, { it.amplifier }, { it.duration }))
            .take(64).map {
                PublicPotionEffect(it.type.key.toString().take(128), it.amplifier.coerceIn(0, 255), ticksToSeconds(it.duration))
            }
        return PublicPotion(
            id = base.lowercase(Locale.ROOT).take(128),
            basePotion = base.take(256),
            form = potionForm(material),
            color = color(meta.color ?: Color.fromRGB(0x385DC6)),
            effects = effects,
        )
    }

    private fun potionForm(material: String): String = when (material) {
        "LINGERING_POTION" -> "LINGERING"
        "SPLASH_POTION" -> "SPLASH"
        else -> "DRINK"
    }

    private fun container(item: ItemStack, meta: org.bukkit.inventory.meta.ItemMeta, depth: Int, context: Context): PublicContainer? {
        if (meta is BlockStateMeta) {
            val shulker = meta.blockState as? ShulkerBox
            if (shulker != null) {
                val contents = shulker.inventory.contents.mapIndexedNotNull { slot, nested ->
                    nested?.takeUnless { it.type.isAir }?.let { PublicContainerEntry(slot, serialize(it, depth + 1, context)) }
                }.take(1024)
                return PublicContainer("SHULKER", shulker.inventory.size, contents.sumOf { it.item.amount },
                    shulker.inventory.size * item.maxStackSize, contents)
            }
        }
        if (meta is BundleMeta) {
            val contents = meta.items.mapIndexed { slot, nested -> PublicContainerEntry(slot, serialize(nested, depth + 1, context)) }
                .take(1024)
            return PublicContainer("BUNDLE", contents.size, contents.sumOf { it.item.amount }, 64, contents)
        }
        return null
    }

    companion object {
        fun ticksToSeconds(ticks: Int): Int = (ticks.coerceAtLeast(0) / 20)
        private fun color(color: Color): String = "#%06X".format(color.asRGB())
        private fun humanName(material: String): String = material.lowercase(Locale.ROOT).split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.take(256)
    }
}
