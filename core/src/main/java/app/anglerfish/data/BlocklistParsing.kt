package app.anglerfish.data

private const val MIN_HOSTS_LINE_TOKENS = 2
private const val SINK_ADDRESS = "0.0.0.0"

// Hosts-file format: "<sink-ip> <domain>" per line, "#"-prefixed comments, blank lines. Only
// "0.0.0.0"-sinked lines are accepted -- StevenBlack's own list header uses 127.0.0.1/::1 for
// localhost/broadcast bootstrapping entries (localhost, local, broadcasthost, ip6-*), which are
// not ad domains and would otherwise block every *.local mDNS name once suffix matching applies.
// The header's own self-referential "0.0.0.0 0.0.0.0" line is also rejected (the domain token
// equals the sink address itself -- not a real domain). Garbled input (an HTML error page, a
// truncated download) just yields whatever valid lines happen to parse under these rules --
// realistically none, since such input won't have "0.0.0.0" as its first token -- never throws,
// so a bad remote response can't take blocking coverage below the bundled snapshot. Extracted
// domains are lowercased so storage is consistent regardless of the source list's own casing.
fun parseHostsFile(text: String): Set<String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val tokens = line.split(Regex("\\s+"))
            val isSinkedDomain = tokens.size >= MIN_HOSTS_LINE_TOKENS &&
                tokens[0] == SINK_ADDRESS &&
                tokens[1] != SINK_ADDRESS
            if (isSinkedDomain) tokens[1].lowercase() else null
        }
        .toSet()

// Suffix-aware: strips the queried domain one dot-separated label at a time and checks set
// membership at each level, so blocking "doubleclick.net" also catches "ads.doubleclick.net".
// Only ever compares whole labels-joined-by-dots, never a raw substring/endsWith check -- that
// would wrongly match "evildoubleclick.net" against a blocked "doubleclick.net". The queried
// domain is lowercased and has any trailing dot stripped before matching -- DNS names are
// case-insensitive, and a fully-qualified name can arrive with a trailing root dot (e.g.
// "doubleclick.net."), neither of which should cause a real match to be missed.
fun isDomainBlocked(domain: String, blocklist: Set<String>): Boolean {
    val normalized = domain.lowercase().removeSuffix(".")
    val labels = normalized.split('.')
    for (startIndex in labels.indices) {
        val candidate = labels.subList(startIndex, labels.size).joinToString(".")
        if (candidate in blocklist) return true
    }
    return false
}
