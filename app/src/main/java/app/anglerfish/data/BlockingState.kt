package app.anglerfish.data

data class BlockingState(
    val selectedPackages: Set<String> = emptySet(),
    val isActive: Boolean = false,
)
