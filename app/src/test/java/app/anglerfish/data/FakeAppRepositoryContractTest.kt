package app.anglerfish.data

import app.anglerfish.ui.FakeAppRepository

class FakeAppRepositoryContractTest : AppRepositoryContractTest() {
    override fun createRepository(): AppRepository = FakeAppRepository()
}
