package app.anglerfish.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Seam between DataStoreBlocklistRepository and the real network call, so refreshIfStale's
// interval/success/failure logic is unit-testable without a live server (same pattern as
// VpnGateway sitting between AppListViewModel and the real VpnController).
interface BlocklistFetcher {
    fun fetch(url: String): String?
}

class HttpBlocklistFetcher : BlocklistFetcher {
    override fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (@Suppress("SwallowedException") e: IOException) {
            // Deliberately swallowed: refreshIfStale's whole contract is "a failed fetch silently
            // keeps the last-known-good list" (no network, timeout, DNS failure, etc. all look the
            // same to a caller that just wants null back).
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
