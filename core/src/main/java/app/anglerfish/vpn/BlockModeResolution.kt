package app.anglerfish.vpn

import app.anglerfish.data.BlockMode

// Pure decision, independent of how ownedPackages was obtained -- PackageManager.getPackagesForUid
// can return more than one package for a shared uid (Android's sharedUserId mechanism), so this
// picks deterministically rather than relying on set iteration order. Returning null (not a default
// BlockMode) keeps "fail closed to FullBlock" the caller's explicit decision, not buried here.
fun resolveBlockMode(ownedPackages: Set<String>, selectedPackages: Map<String, BlockMode>): BlockMode? =
    ownedPackages.firstNotNullOfOrNull { selectedPackages[it] }
