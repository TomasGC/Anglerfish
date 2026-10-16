package app.anglerfish.vpn

import app.anglerfish.data.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockModeResolutionTest {

    @Test
    fun `a selected package resolves to its own mode`() {
        val mode = resolveBlockMode(
            ownedPackages = setOf("com.example.app"),
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )
        assertEquals(BlockMode.AdFilterOnly, mode)
    }

    // Review Focus: no owned package at all (uid attribution found nothing to map).
    @Test
    fun `an empty owned-package set fails closed`() {
        val mode = resolveBlockMode(
            ownedPackages = emptySet(),
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )
        assertNull(mode)
    }

    // Review Focus: the uid's package is real but was never selected -- background/system traffic
    // sharing the tunnel with a selected app must not be treated as unfiltered AdFilterOnly.
    @Test
    fun `an owned package that is not selected fails closed`() {
        val mode = resolveBlockMode(
            ownedPackages = setOf("com.other.app"),
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )
        assertNull(mode)
    }

    // Review Focus: a shared uid resolves to more than one package (rare, Android allows it via
    // shared sharedUserId). Deterministic: the first owned package that IS selected wins, not an
    // arbitrary/unordered pick.
    @Test
    fun `a shared uid resolves via whichever owned package is selected`() {
        val mode = resolveBlockMode(
            ownedPackages = setOf("com.other.app", "com.example.app"),
            selectedPackages = mapOf("com.example.app" to BlockMode.FullBlock),
        )
        assertEquals(BlockMode.FullBlock, mode)
    }

    @Test
    fun `a shared uid with no selected package among it fails closed`() {
        val mode = resolveBlockMode(
            ownedPackages = setOf("com.other.app", "com.another.app"),
            selectedPackages = mapOf("com.example.app" to BlockMode.FullBlock),
        )
        assertNull(mode)
    }
}
