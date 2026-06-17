package net.badgersmc.em.application

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TC-1 (REQ-283): pure case-insensitive prefix filter for `/shop search` tab-completion.
 * Red until [MaterialSuggestions.matching] is implemented.
 */
class MaterialSuggestionsTest {

    private val sample = listOf("ACACIA_BOAT", "AMETHYST_SHARD", "APPLE", "DIAMOND", "DIAMOND_SWORD")

    @Test
    fun `prefix match is case-insensitive (lowercase input)`() {
        assertEquals(listOf("DIAMOND", "DIAMOND_SWORD"), MaterialSuggestions.matching(sample, "dia"))
    }

    @Test
    fun `prefix match works for uppercase input`() {
        assertEquals(listOf("APPLE"), MaterialSuggestions.matching(sample, "APP"))
    }

    @Test
    fun `blank input returns every candidate in original order`() {
        assertEquals(sample, MaterialSuggestions.matching(sample, ""))
    }

    @Test
    fun `no match returns empty list`() {
        assertEquals(emptyList(), MaterialSuggestions.matching(sample, "zzz"))
    }
}
