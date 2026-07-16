package net.badgersmc.em.websync

/** Reuses the head-upload client until the effective sync configuration changes. */
internal class HeadUploadClientCache(
    private val factory: (WebsiteSyncConfig) -> MarketHttpClient,
) {
    private var config: WebsiteSyncConfig? = null
    private var client: MarketHttpClient? = null

    @Synchronized
    fun client(config: WebsiteSyncConfig): MarketHttpClient {
        if (client == null || this.config != config) {
            this.config = config
            client = factory(config)
        }
        return requireNotNull(client)
    }

    @Synchronized
    fun clear() {
        config = null
        client = null
    }
}
