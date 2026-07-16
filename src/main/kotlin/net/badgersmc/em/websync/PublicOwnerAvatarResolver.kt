package net.badgersmc.em.websync

import org.geysermc.floodgate.api.FloodgateApi
import java.util.UUID

/** Produces bounded, public head metadata without resolving skin bytes server-side. */
class PublicOwnerAvatarResolver(
    private val capturedHead: (UUID) -> String? = { null },
    private val floodgatePlayer: (UUID) -> Boolean = ::isFloodgatePlayer,
) {
    data class Result(val source: String, val url: String?)

    @Suppress("UnusedParameter")
    fun resolve(uuid: UUID, playerName: String?): Result {
        val floodgate = runCatching { floodgatePlayer(uuid) }.getOrDefault(false)
        val proxyStyle = uuid.toString().startsWith(PROXY_UUID_PREFIX)
        if (floodgate || proxyStyle) {
            capturedHead(uuid)?.let { return Result("BEDROCK_CAPTURED", it) }
            val source = if (floodgate) "FLOODGATE" else "PROXY"
            return Result(source, null)
        }
        return Result("JAVA", "$MINOTAR_HELM/${uuid}/$HEAD_SIZE.png")
    }

    private companion object {
        const val MINOTAR_HELM = "https://minotar.net/helm"
        const val HEAD_SIZE = 96
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
