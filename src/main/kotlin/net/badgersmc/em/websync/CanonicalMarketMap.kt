package net.badgersmc.em.websync

import com.google.gson.reflect.TypeToken

data class CanonicalStall(val id: String, val buildingId: String, val floor: Int)
data class CanonicalProvenance(
    val mapperCommit: String,
    val sourceSha256: String,
    val approvedPolygonFingerprint: String,
    val recordCount: Int,
)

class CanonicalMarketMap private constructor(
    val stalls: Map<String, CanonicalStall>,
    val provenance: CanonicalProvenance,
) {
    /** Stable-sorted canonical stall identifiers (e.g. stall1..stall71). */
    val stallIds: List<String> = stalls.keys.sortedBy { it.removePrefix("stall").toInt() }
    val stallCount: Int get() = stalls.size

    fun validate(): List<String> {
        val expected = (1..stallCount).map { "stall$it" }.toSet()
        val errors = mutableListOf<String>()
        if (stalls.keys != expected) errors += "canonical_ids"
        if (stalls.size != stallCount || provenance.recordCount != stallCount) errors += "canonical_count"
        if (provenance.mapperCommit != MAPPER_COMMIT || provenance.sourceSha256 != SOURCE_SHA256 ||
            provenance.approvedPolygonFingerprint != POLYGON_FINGERPRINT
        ) errors += "canonical_provenance"
        if (stalls.values.any { !it.buildingId.matches(Regex("building-[1-9][0-9]*")) || it.floor !in -64..1024 }) {
            errors += "canonical_values"
        }
        if (!stalls.containsKey("stall60") || !stalls.containsKey("stall62")) errors += "canonical_duplicate_keys"
        return errors
    }

    companion object {
        const val MAPPER_COMMIT = "f35e53c22d30191546330ba84bcefd839cc65ee7"
        const val SOURCE_SHA256 = "ac49bd18f335f453e7c788d16a53fae22f1fd4187227660baec3134c433cb302"
        const val POLYGON_FINGERPRINT = "6f6d926c79fecbcf250043aab2445dccc94c60d92ff70bc042ac8b4650f5b2d8"

        fun load(classLoader: ClassLoader = CanonicalMarketMap::class.java.classLoader): CanonicalMarketMap {
            val stallsJson = classLoader.getResourceAsStream("market/canonical-market-stalls.json")
                ?.bufferedReader()?.use { it.readText() } ?: error("Canonical stall map is missing")
            val provenanceJson = classLoader.getResourceAsStream("market/canonical-market-stalls.provenance.json")
                ?.bufferedReader()?.use { it.readText() } ?: error("Canonical provenance is missing")
            val type = object : TypeToken<List<CanonicalStall>>() {}.type
            val list: List<CanonicalStall> = com.google.gson.Gson().fromJson(stallsJson, type)
            val provenance = WebsiteSyncJson.parse(provenanceJson, CanonicalProvenance::class.java)
            return CanonicalMarketMap(list.associateBy { it.id }, provenance)
        }

        fun unavailable(): CanonicalMarketMap = CanonicalMarketMap(
            emptyMap(), CanonicalProvenance("", "", "", 0)
        )
    }
}
