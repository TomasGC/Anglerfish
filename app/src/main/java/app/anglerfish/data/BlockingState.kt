package app.anglerfish.data

data class BlockingState(
    val selectedPackages: Map<String, BlockMode> = emptyMap(),
    val isActive: Boolean = false,
    val hiddenPackages: Set<String> = emptySet(),
)
