package app.anglerfish.ui

import app.anglerfish.data.AppRepository
import app.anglerfish.data.BlockingState
import app.anglerfish.data.InstalledApp
import app.anglerfish.vpn.VpnGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakeAppRepository : AppRepository {
        private val flow = MutableStateFlow(BlockingState())
        override val state: Flow<BlockingState> = flow
        override suspend fun toggleSelection(packageName: String) {
            val current = flow.value.selectedPackages
            flow.value = flow.value.copy(
                selectedPackages = if (packageName in current) current - packageName else current + packageName,
            )
        }
        override suspend fun setActive(active: Boolean) {
            flow.value = flow.value.copy(isActive = active)
        }
    }

    private class FakeVpnGateway : VpnGateway {
        var consentNeeded = false
        var startedWith: Set<String>? = null
        var stopped = false
        override fun needsConsent() = consentNeeded
        override fun start(selectedPackages: Set<String>) { startedWith = selectedPackages }
        override fun restart(selectedPackages: Set<String>) { startedWith = selectedPackages }
        override fun stop() { stopped = true }
    }

    private val installedApps = listOf(InstalledApp("com.example.one", "One"))

    @Test
    fun `activating with no selection emits SelectionEmpty and does not start the vpn`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway()
        val viewModel = AppListViewModel(repository, gateway, installedApps)

        viewModel.onActivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppListEvent.SelectionEmpty, viewModel.eventFlow.first())
        assertEquals(null, gateway.startedWith)
    }

    @Test
    fun `activating with a selection and no consent needed starts the vpn and marks state active`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway()
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
        val gateway = FakeVpnGateway().apply { consentNeeded = true }
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
        val gateway = FakeVpnGateway()
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
        val gateway = FakeVpnGateway()
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)

        viewModel.toggleApp("com.example.one")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gateway.stopped)
        assertEquals(false, repository.state.first().isActive)
    }

    @Test
    fun `toggling a selection while active restarts the vpn with the new set`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway()
        val twoApps = listOf(InstalledApp("com.example.one", "One"), InstalledApp("com.example.two", "Two"))
        val viewModel = AppListViewModel(repository, gateway, twoApps)
        repository.toggleSelection("com.example.one")
        repository.setActive(true)

        viewModel.toggleApp("com.example.two")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("com.example.one", "com.example.two"), gateway.startedWith)
    }

    @Test
    fun `onDeactivateClicked stops the vpn and marks state inactive`() = runTest(dispatcher) {
        val repository = FakeAppRepository()
        val gateway = FakeVpnGateway()
        val viewModel = AppListViewModel(repository, gateway, installedApps)
        repository.setActive(true)

        viewModel.onDeactivateClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gateway.stopped)
        assertEquals(false, repository.state.first().isActive)
    }
}
