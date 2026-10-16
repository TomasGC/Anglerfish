package app.anglerfish.ui

import app.anglerfish.data.AppListLayout
import app.anglerfish.data.AppRepository
import app.anglerfish.data.BlockMode
import app.anglerfish.data.BlockingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Shared between AppListViewModelTest (ViewModel behavior against a fake) and
// AppRepositoryContractTest's subclasses (verifies this fake's behavior matches
// DataStoreAppRepository's real behavior, so the two can't silently drift again -- this already
// happened twice in one session while building per-app BlockMode, see issue #38's kanban entry).
class FakeAppRepository : AppRepository {
    private val flow = MutableStateFlow(BlockingState())
    private val layoutFlow = MutableStateFlow(AppListLayout.LIST)
    // Test-only: simulates another caller (e.g. onDeactivateClicked) completing a
    // setActive(false) while this toggleHidden call is still in flight, to reproduce the
    // race between reading state before and after the repository call.
    var deactivateDuringToggleHidden = false
    // #62: counts real setActive(...) calls, separate from markInactiveFromGatewayStop()'s
    // simulated out-of-band write -- lets a test prove the ViewModel itself never calls
    // setActive(false) directly any more (see onDeactivateClicked's regression test).
    var setActiveCallCount = 0
    override val state: Flow<BlockingState> = flow
    override val layout: Flow<AppListLayout> = layoutFlow
    override suspend fun toggleSelection(packageName: String) {
        val current = flow.value.selectedPackages
        flow.value = flow.value.copy(
            selectedPackages = if (packageName in current) {
                current - packageName
            } else {
                current + (packageName to BlockMode.AdFilterOnly)
            },
        )
    }
    override suspend fun setMode(packageName: String, mode: BlockMode) {
        flow.value = flow.value.copy(
            selectedPackages = flow.value.selectedPackages + (packageName to mode),
            hiddenPackages = flow.value.hiddenPackages - packageName,
        )
    }
    override suspend fun toggleHidden(packageName: String) {
        val currentHidden = flow.value.hiddenPackages
        val nowHidden = packageName !in currentHidden
        flow.value = flow.value.copy(
            hiddenPackages = if (nowHidden) currentHidden + packageName else currentHidden - packageName,
            selectedPackages = if (nowHidden) flow.value.selectedPackages - packageName else flow.value.selectedPackages,
            isActive = if (deactivateDuringToggleHidden) false else flow.value.isActive,
        )
    }
    override suspend fun setActive(active: Boolean) {
        setActiveCallCount++
        flow.value = flow.value.copy(isActive = active)
    }

    // #62: production's single writer of isActive=false on deactivate is now
    // AnglerfishVpnService's ACTION_DEACTIVATE handler, not the ViewModel -- FakeVpnGateway.stop()
    // calls this to simulate that write landing, without needing a suspend fun (VpnGateway.stop()
    // is fire-and-forget, same as the real Intent-based call).
    fun markInactiveFromGatewayStop() {
        flow.value = flow.value.copy(isActive = false)
    }
    override suspend fun setLayout(layout: AppListLayout) {
        layoutFlow.value = layout
    }
}
