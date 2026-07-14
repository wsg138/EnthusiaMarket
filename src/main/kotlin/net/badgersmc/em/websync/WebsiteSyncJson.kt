package net.badgersmc.em.websync

import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

object WebsiteSyncJson {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val includedRevisionsType = object : com.google.gson.reflect.TypeToken<List<IncludedRevision>>() {}.type
    fun bytes(value: Any): ByteArray = gson.toJson(value).toByteArray(StandardCharsets.UTF_8)
    fun <T> parse(text: String, type: Class<T>): T = gson.fromJson(text, type)
    fun fromJsonIncludedRevisions(json: ByteArray): List<IncludedRevision> =
        gson.fromJson(json.toString(Charsets.UTF_8), includedRevisionsType)
}
