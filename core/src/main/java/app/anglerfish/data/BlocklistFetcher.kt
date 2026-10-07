package app.anglerfish.data

// Seam between DataStoreBlocklistRepository and the real network call, so refreshIfStale's
// interval/success/failure logic is unit-testable without a live server (same pattern as
// VpnGateway sitting between AppListViewModel and the real VpnController).
interface BlocklistFetcher {
    fun fetch(url: String): String?
}
