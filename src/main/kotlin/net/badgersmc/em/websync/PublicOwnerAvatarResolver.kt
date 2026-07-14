package net.badgersmc.em.websync

import org.geysermc.floodgate.api.FloodgateApi
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Produces bounded, public head metadata without resolving skin bytes server-side. */
class PublicOwnerAvatarResolver(
    private val floodgatePlayer: (UUID) -> Boolean = ::isFloodgatePlayer,
) {
    data class Result(val source: String, val url: String?)

    fun resolve(uuid: UUID, playerName: String?): Result {
        val floodgate = runCatching { floodgatePlayer(uuid) }.getOrDefault(false)
        val proxyStyle = uuid.toString().startsWith(PROXY_UUID_PREFIX)
        if (floodgate || proxyStyle) {
            val source = if (floodgate) "FLOODGATE" else "PROXY"
            return Result(source, playerName?.takeIf(::isUsableName)?.let(::nameHeadUrl))
        }
        return Result("JAVA", "$MINOTAR_HELM/${uuid}/$HEAD_SIZE.png")
    }

    private fun nameHeadUrl(name: String): String {
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
        return "$MINOTAR_HELM/$encoded/$HEAD_SIZE.png"
    }

    private fun isUsableName(name: String): Boolean = name.isNotBlank() && name.length <= MAX_PLAYER_NAME_LENGTH

    private companion object {
        const val MINOTAR_HELM = "https://minotar.net/helm"
        const val HEAD_SIZE = 96
        const val MAX_PLAYER_NAME_LENGTH = 64
        const val PROXY_UUID_PREFIX = "00000000-0000-0000-"

        fun isFloodgatePlayer(uuid: UUID): Boolean = try {
            FloodgateApi.getInstance().isFloodgatePlayer(uuid)
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
