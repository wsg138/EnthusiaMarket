package net.badgersmc.em.websync

import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

object WebsiteSyncJson {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    fun bytes(value: Any): ByteArray = gson.toJson(value).toByteArray(StandardCharsets.UTF_8)
    fun <T> parse(text: String, type: Class<T>): T = gson.fromJson(text, type)
}
