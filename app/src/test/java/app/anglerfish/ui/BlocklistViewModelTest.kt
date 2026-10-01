package app.anglerfish.ui

import app.anglerfish.data.BlocklistRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlocklistViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBlocklistRepository : BlocklistRepository {
        private val additions = MutableStateFlow(emptySet<String>())
        override val userAdditions: Flow<Set<String>> = additions
        override suspend fun addDomain(domain: String) {
            additions.value = additions.value + domain
        }
        override suspend fun removeDomain(domain: String) {
            additions.value = additions.value - domain
        }
        override suspend fun isBlocked(domain: String): Boolean = domain in additions.value
        override suspend fun refreshIfStale() {}
    }

    @Test
    fun `addDomain persists a non-blank domain via the repository`() = runTest(dispatcher) {
        val repository = FakeBlocklistRepository()
        val viewModel = BlocklistViewModel(repository)

        viewModel.addDomain("mytracker.example")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("mytracker.example"), repository.userAdditions.first())
    }

    @Test
    fun `addDomain trims surrounding whitespace`() = runTest(dispatcher) {
        val repository = FakeBlocklistRepository()
        val viewModel = BlocklistViewModel(repository)

        viewModel.addDomain("  mytracker.example  ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("mytracker.example"), repository.userAdditions.first())
    }

    @Test
    fun `addDomain with a blank string does not persist anything`() = runTest(dispatcher) {
        val repository = FakeBlocklistRepository()
        val viewModel = BlocklistViewModel(repository)

        viewModel.addDomain("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptySet<String>(), repository.userAdditions.first())
    }

    @Test
    fun `addDomain lowercases the domain before persisting`() = runTest(dispatcher) {
        val repository = FakeBlocklistRepository()
        val viewModel = BlocklistViewModel(repository)

        viewModel.addDomain("MyTracker.Example")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("mytracker.example"), repository.userAdditions.first())
    }

    @Test
    fun `removeDomain removes the domain via the repository`() = runTest(dispatcher) {
        val repository = FakeBlocklistRepository()
        val viewModel = BlocklistViewModel(repository)
        viewModel.addDomain("mytracker.example")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.removeDomain("mytracker.example")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptySet<String>(), repository.userAdditions.first())
    }
}
