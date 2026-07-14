package net.badgersmc.em.websync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MarketRequestSigner {
    fun bodyHash(body: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(body))

    fun sign(
        secret: String,
        method: String,
        pathname: String,
        serverId: String,
        timestamp: String,
        eventId: String,
        body: ByteArray,
    ): String {
        require(method == method.uppercase()) { "Method must be uppercase" }
        require(pathname.startsWith('/') && !pathname.contains('?')) { "Pathname only is required" }
        val canonical = listOf("v1", method, pathname, serverId, timestamp, eventId, bodyHash(body)).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "v1=${hex(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
