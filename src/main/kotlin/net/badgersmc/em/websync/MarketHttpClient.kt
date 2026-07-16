package net.badgersmc.em.websync

import com.google.gson.JsonParser
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class DeliveryOutcome {
    data object Success : DeliveryOutcome()
    data class Retry(val retryAfterMillis: Long? = null) : DeliveryOutcome()
    data class Reconcile(val category: String) : DeliveryOutcome()
    data class Pause(val category: String) : DeliveryOutcome()
}

class MarketHttpClient(
    private val config: WebsiteSyncConfig,
    private val userAgent: String = "EnthusiaMarket/0.2.0",
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun deliver(delivery: PendingDelivery): DeliveryOutcome {
        val path = when (delivery.kind) {
            DeliveryKind.FULL -> "/internal/v1/full-sync"
            DeliveryKind.STALL -> "/internal/v1/stalls/${delivery.stallId}"
        }
        val method = if (delivery.kind == DeliveryKind.FULL) "POST" else "PUT"
        return send(method, path, delivery.eventId, delivery.body)
    }

    fun authenticatedTest(serverEpoch: String): DeliveryOutcome {
        val eventId = java.util.UUID.randomUUID().toString()
        val body = WebsiteSyncJson.bytes(
            TestRequest(serverEpoch = serverEpoch, eventId = eventId, sentAt = Instant.now().toString(), probe = "website-sync-test")
        )
        require(body.size <= 32 * 1024) { "test_body_limit" }
        return send("POST", "/internal/v1/test", eventId, body, requireAuthenticated = true)
    }

    fun uploadPlayerHead(playerId: java.util.UUID, hash: String, png: ByteArray): DeliveryOutcome {
        val eventId = java.util.UUID.randomUUID().toString()
        return send(
            method = "PUT",
            path = "/internal/v1/player-heads/$hash.png",
            eventId = eventId,
            body = png,
            contentType = "image/png",
            extraHeaders = mapOf("X-Enthusia-Player-Id" to playerId.toString()),
            responseValidator = { validPlayerHeadResponse(it, hash) },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun send(
        method: String,
        path: String,
        eventId: String,
        body: ByteArray,
        requireAuthenticated: Boolean = false,
        contentType: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        responseValidator: ((ByteArray) -> Boolean)? = null,
    ): DeliveryOutcome {
        return try {
            val timestamp = System.currentTimeMillis().toString()
            val signature = MarketRequestSigner.sign(config.secret, method, path, config.serverId, timestamp, eventId, body)
            val builder = HttpRequest.newBuilder(config.endpoint.resolve(path))
                .timeout(config.requestTimeout)
                .header("Content-Type", contentType)
                .header("User-Agent", userAgent)
                .header("X-Enthusia-Server-Id", config.serverId)
                .header("X-Enthusia-Timestamp", timestamp)
                .header("X-Enthusia-Event-Id", eventId)
                .header("X-Enthusia-Signature", signature)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            extraHeaders.forEach(builder::header)
            val request = builder.build()
            classify(client.send(request, HttpResponse.BodyHandlers.ofByteArray()), requireAuthenticated, responseValidator)
        } catch (_: java.net.http.HttpTimeoutException) {
            DeliveryOutcome.Retry()
        } catch (_: java.io.IOException) {
            DeliveryOutcome.Retry()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DeliveryOutcome.Retry()
        } catch (_: Exception) {
            DeliveryOutcome.Pause("request_failure")
        }
    }

    private fun classify(
        response: HttpResponse<ByteArray>,
        requireAuthenticated: Boolean,
        responseValidator: ((ByteArray) -> Boolean)?,
    ): DeliveryOutcome {
        val status = response.statusCode()
        if (status in 200..299) {
            if (requireAuthenticated && !authenticated(response.body())) return DeliveryOutcome.Pause("invalid_test_response")
            if (responseValidator != null && !responseValidator(response.body())) {
                return DeliveryOutcome.Pause("invalid_head_response")
            }
            return DeliveryOutcome.Success
        }
        // Check transient/retryable statuses first — a 429/5xx body may coincidentally
        // contain a reconciliation-code string, but we must back off, not reconcile.
        if (status == 408 || status == 429 || status >= 500) {
            return DeliveryOutcome.Retry(retryAfter(response.headers().firstValue("Retry-After").orElse(null)))
        }
        val code = safeCode(response.body())
        if (status == 409 && code in RECONCILE_CODES) return DeliveryOutcome.Reconcile(code!!)
        val category = if (status == 400) safeDiagnosticCategory(response.body()) else null
        return DeliveryOutcome.Pause(category ?: pauseCategory(status))
    }

    private fun authenticated(bytes: ByteArray): Boolean = runCatching {
        if (bytes.size > 64 * 1024) return@runCatching false
        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            .get("authenticated")?.asBoolean == true
    }.getOrDefault(false)

    private fun validPlayerHeadResponse(bytes: ByteArray, hash: String): Boolean = runCatching {
        if (bytes.size > 64 * 1024) return@runCatching false
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        root.get("ok")?.asBoolean == true && root.get("hash")?.asString == hash &&
            root.get("url")?.asString == "https://market-api.enthusia.info/v1/player-heads/$hash.png" &&
            root.get("duplicate")?.isJsonPrimitive == true && root.get("duplicate").asJsonPrimitive.isBoolean
    }.getOrDefault(false)

    private fun pauseCategory(status: Int): String = when (status) {
        400 -> "bad_request"
        401 -> "unauthorized"
        403 -> "forbidden"
        413 -> "payload_too_large"
        in 300..399 -> "http_3xx"
        else -> "http_4xx"
    }

    private fun safeCode(bytes: ByteArray): String? = runCatching {
        if (bytes.size > 64 * 1024) return@runCatching null
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        root.getAsJsonObject("error")?.get("code")?.asString ?: root.get("code")?.asString
    }.getOrNull()

    private fun safeDiagnosticCategory(bytes: ByteArray): String? = runCatching {
        if (bytes.size > 64 * 1024) return@runCatching null
        val diagnostic = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            .getAsJsonObject("error")?.getAsJsonObject("diagnostic") ?: return@runCatching null
        if (diagnostic.get("category")?.asString != "invalid_field") return@runCatching null
        val path = diagnostic.getAsJsonArray("issues")?.firstOrNull()?.asJsonObject
            ?.get("path")?.asString ?: return@runCatching null
        if (path.length !in 1..160 || !SAFE_DIAGNOSTIC_PATH.matches(path)) return@runCatching null
        val fields = SAFE_DIAGNOSTIC_FIELD.findAll(path).map { it.value }.toList()
        if (fields.isEmpty() || fields.any { it !in SAFE_DIAGNOSTIC_FIELDS }) return@runCatching null
        "invalid_field:${path.replace(Regex("""\[\d+]"""), "[]")}"
    }.getOrNull()

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                System.currentTimeMillis()).coerceIn(0, 86_400_000)
        }.getOrNull()
    }

    companion object {
        private val SAFE_DIAGNOSTIC_PATH = Regex("""^[A-Za-z][A-Za-z0-9]*(?:\[\d+]|\.[A-Za-z][A-Za-z0-9]*)*$""")
        private val SAFE_DIAGNOSTIC_FIELD = Regex("""[A-Za-z][A-Za-z0-9]*""")
        private val SAFE_DIAGNOSTIC_FIELDS = setOf(
            "schemaVersion", "serverId", "serverEpoch", "eventId", "sentAt", "snapshotRevision", "generatedAt",
            "stalls", "revision", "stall", "id", "buildingId", "floor", "location", "world", "x", "y", "z",
            "owner", "type", "uuid", "name", "avatarUrl", "avatar", "kind", "source", "includesOuterLayer", "url",
            "ownerSince", "nextRentAt", "members", "shops", "direction", "sellItem", "sellAmount", "costItem",
            "costAmount", "interaction", "stockCount", "availableTrades", "searchable", "material", "displayName",
            "amount", "icon", "metadata", "customName", "enchantments", "storedEnchantments", "potion", "armorTrim",
            "smithingTemplate", "writtenBook", "shulkerColor", "container", "level", "basePotion", "form", "color",
            "effects", "amplifier", "durationSeconds", "pattern", "title", "author", "generation", "pageCount", "slots",
            "capacityUsed", "capacityMax", "contents", "slot", "item", "probe",
        )
        private val RECONCILE_CODES = setOf(
            "market_not_initialized", "epoch_mismatch", "stale_revision", "stale_snapshot", "stall_not_found"
        )
    }
}
