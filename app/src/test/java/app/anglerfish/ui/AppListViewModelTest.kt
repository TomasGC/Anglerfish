package app.anglerfish.ui

import app.anglerfish.data.AppListLayout
import app.anglerfish.data.BlockMode
import app.anglerfish.data.InstalledApp
import app.anglerfish.vpn.VpnGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // #62: takes the repository so stop() can simulate AnglerfishVpnService's ACTION_DEACTIVATE
    // handler persisting isActive=false -- that write moved out of the ViewModel (see
    // onDeactivateClicked) to close a real deadlock between it and the service's own write racing
    // for DataStore's internal lock on the same key, so the fake must model it here instead.
    private class FakeVpnGateway(private val repository: FakeAppRepository) : VpnGateway {
        var consentNeeded = false
        var startedWith: Set<String>? = null
        var stopped = false
        override fun needsConsent() = consentNeeded
        override fun start(selectedPackages: Set<String>) { startedWith = selectedPackages }
        override fun restart(selectedPackages: Set<String>) { startedWith = selectedPackages }
        override fun stop() {
            stopped = true
            repository.markInactiveFromGatewayStop()
        }
    }

    private val installedApps = listOf(InstalledApp("com.example.one", "One"))

    @Test
    fun `activating with no selection emits SelectionEmpty and does not start the vpn`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        viewModel.onActivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppListEvent.SelectionEmpty, viewModel.eventFlow.first())
        assertEquals(null, gateway.startedWith)
    }

    @Test
    fun `activating with a selection and no consent needed starts the vpn and marks state active`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")

        viewModel.onActivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("com.example.one"), gateway.startedWith)
        assertTrue(repository.state.first().isActive)
    }

    @Test
    fun `activating when consent is needed emits VpnConsentRequired and does not start yet`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository).apply { consentNeeded = true }
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")

        viewModel.onActivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppListEvent.VpnConsentRequired, viewModel.eventFlow.first())
        assertEquals(null, gateway.startedWith)
    }

    @Test
    fun `onConsentGranted starts the vpn with the current selection`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")

        viewModel.onConsentGranted()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("com.example.one"), gateway.startedWith)
        assertTrue(repository.state.first().isActive)
    }

    @Test
    fun `unselecting the last app while active stops the vpn instead of restarting with an empty set`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)

        repository.setActiveCallCount = 0
        viewModel.toggleApp("com.example.one")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gateway.stopped)
        assertEquals(false, repository.state.first().isActive)
        // #62: same single-writer fix as onDeactivateClicked -- toggleApp's empty-selection branch
        // must not call repository.setActive(false) itself either.
        assertEquals(0, repository.setActiveCallCount)
    }

    @Test
    fun `toggling a selection while active restarts the vpn with the new set`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)

        viewModel.toggleApp("com.example.two")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("com.example.one", "com.example.two"), gateway.startedWith)
    }

    @Test
    fun `construction restarts the vpn when persisted state is active with a non-empty selection`() =
        runTest(dispatcher) {
            val repository = FakeAppRepository()
            val gateway = FakeVpnGateway(repository)
            repository.toggleSelection("com.example.one")
            repository.setActive(true)

            AppListViewModel(repository, gateway, installedApps)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("com.example.one"), gateway.startedWith)
        }

    @Test
    fun `construction does not restart the vpn when persisted state is inactive`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        repository.toggleSelection("com.example.one")

        AppListViewModel(repository, gateway, installedApps)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, gateway.startedWith)
    }

    @Test
    fun `onDeactivateClicked stops the vpn and marks state inactive`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.setActive(true)
        repository.setActiveCallCount = 0

        viewModel.onDeactivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gateway.stopped)
        assertEquals(false, repository.state.first().isActive)
        // #62: onDeactivateClicked must not call repository.setActive itself -- that write raced
        // AnglerfishVpnService's own ACTION_DEACTIVATE write for DataStore's internal lock and
        // deadlocked the main thread (confirmed on-device via an ANR trace parked in runBlocking
        // at AnglerfishVpnService.kt:84). The service is now the single writer; the gateway's
        // stop() simulates that write landing instead.
        assertEquals(0, repository.setActiveCallCount)
    }

    @Test
    fun `search query filters the visible apps by label, case-insensitive`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val threeApps = listOf(
            InstalledApp("com.example.one", "Anglerfish"),
            InstalledApp("com.example.two", "Otter"),
            InstalledApp("com.example.three", "OTHER"),
        )
        val viewModel = AppListViewModel(repository, gateway, threeApps)

        viewModel.onSearchQueryChanged("ot")
        val visible = viewModel.uiState.first().notSelectedApps.map { it.packageName }

        assertEquals(setOf("com.example.two", "com.example.three"), visible.toSet())
    }

    @Test
    fun `clearing the search query restores the full list`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)

        viewModel.onSearchQueryChanged("One")
        viewModel.onSearchQueryChanged("")
        val visible = viewModel.uiState.first().notSelectedApps.map { it.packageName }

        assertEquals(setOf("com.example.one", "com.example.two"), visible.toSet())
    }

    @Test
    fun `toggling a selection while search-filtered updates the persisted selection normally`() =
        runTest(dispatcher) {
            val repository = FakeAppRepository()
            val gateway = FakeVpnGateway(repository)
            val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
            val viewModel = AppListViewModel(repository, gateway, twoApps)
            viewModel.onSearchQueryChanged("One")

            viewModel.toggleApp("com.example.two")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(mapOf("com.example.two" to BlockMode.AdFilterOnly), repository.state.first().selectedPackages)
        }

    @Test
    fun `uiState reflects the persisted layout`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        assertEquals(AppListLayout.LIST, viewModel.uiState.first().layout)
    }

    @Test
    fun `toggleLayout switches from list to grid and persists it`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        viewModel.toggleLayout()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppListLayout.GRID, repository.layout.first())
        assertEquals(AppListLayout.GRID, viewModel.uiState.first().layout)
    }

    @Test
    fun `toggleLayout twice returns to list`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        viewModel.toggleLayout()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleLayout()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppListLayout.LIST, repository.layout.first())
    }

    @Test
    fun `grid layout still respects the active search filter`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)
        viewModel.onSearchQueryChanged("One")

        viewModel.toggleLayout()
        dispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.first()

        assertEquals(AppListLayout.GRID, state.layout)
        assertEquals(listOf("com.example.one"), state.notSelectedApps.map { it.packageName })
    }

    @Test
    fun `toggleHidden moves an app from notSelectedApps to hiddenApps`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        viewModel.toggleHidden("com.example.one")
        dispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.first()

        assertEquals(emptyList<String>(), state.notSelectedApps.map { it.packageName })
        assertEquals(listOf("com.example.one"), state.hiddenApps.map { it.packageName })
    }

    @Test
    fun `toggleHidden twice returns an app to notSelectedApps and leaves its selection untouched`() =
        runTest(dispatcher) {
            val repository = FakeAppRepository()
            val gateway = FakeVpnGateway(repository)
            val viewModel = AppListViewModel(repository, gateway, installedApps)

            viewModel.toggleHidden("com.example.one")
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.toggleHidden("com.example.one")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyMap<String, BlockMode>(), repository.state.first().selectedPackages)
            assertEquals(listOf("com.example.one"), viewModel.uiState.first().notSelectedApps.map { it.packageName })
        }

    @Test
    fun `hiding a selected app while active stops the vpn when it was the last selection`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)

        repository.setActiveCallCount = 0
        viewModel.toggleHidden("com.example.one")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gateway.stopped)
        assertEquals(false, repository.state.first().isActive)
        // #62: same single-writer fix -- toggleHidden's empty-selection-after-hide branch must not
        // call repository.setActive(false) itself either.
        assertEquals(0, repository.setActiveCallCount)
    }

    @Test
    fun `hiding a selected app while active restarts the vpn with the remaining selection`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)
        repository.toggleSelection("com.example.one")
        repository.toggleSelection("com.example.two")
        repository.setActive(true)

        viewModel.toggleHidden("com.example.one")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("com.example.two"), gateway.startedWith)
    }

    @Test
    fun `hiding a not-selected app while active does not restart the vpn`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)
        dispatcher.scheduler.advanceUntilIdle()
        gateway.startedWith = null

        viewModel.toggleHidden("com.example.two")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, gateway.startedWith)
    }

    @Test
    fun `search query filters within all three buckets`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway(repository)
        val apps = listOf(
            InstalledApp("com.example.selected", "Select Me"),
            InstalledApp("com.example.notselected", "Skip Me"),
            InstalledApp("com.example.hidden", "Select Hidden"),
        )
        val viewModel = AppListViewModel(repository, gateway, apps)
        repository.toggleSelection("com.example.selected")
        repository.toggleHidden("com.example.hidden")

        viewModel.onSearchQueryChanged("select")
        val state = viewModel.uiState.first()

        assertEquals(listOf("com.example.selected"), state.selectedApps.map { it.packageName })
        assertEquals(emptyList<String>(), state.notSelectedApps.map { it.packageName })
        assertEquals(listOf("com.example.hidden"), state.hiddenApps.map { it.packageName })
    }

    @Test
    fun `toggleHidden does not restart the vpn if it was deactivated during the same operation`() =
        runTest(dispatcher) {
            val repository = FakeAppRepository()
            val gateway = FakeVpnGateway(repository)
            val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
            val viewModel = AppListViewModel(repository, gateway, twoApps)
            dispatcher.scheduler.advanceUntilIdle() // flush construction-time reconciliation (no-op: default state)
            repository.toggleSelection("com.example.one")
            repository.toggleSelection("com.example.two")
            repository.setActive(true)
            repository.deactivateDuringToggleHidden = true

            viewModel.toggleHidden("com.example.one")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(null, gateway.startedWith)
        }
}
