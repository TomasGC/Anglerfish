# Tests - Anglerfish

**Last Updated**: 2026-09-21

---

## Counts (verified via `./gradlew testDebugUnitTest`, JUnit XML reports)

| Category | Tests | Runner | Description |
|----------|-------|--------|--------------|
| Unit (`data/`) | 4 + 4 | JUnit + kotlinx-coroutines-test | `AppListFilterTest` (pure filter logic), `DataStoreAppRepositoryTest` (JVM-only DataStore via `PreferenceDataStoreFactory` + `TemporaryFolder`) |
| Unit (`ui/`) | 7 | JUnit + kotlinx-coroutines-test | `AppListViewModelTest` against `FakeAppRepository`/`FakeVpnGateway` — empty-selection guard, consent-needed branch, activate/deactivate, auto-restart and auto-stop on selection change |
| Manual (`vpn/`) | 8 scenarios | On-device checklist | `docs/manual-testing.md` — first-run consent, activate/deactivate, notification deactivate action, empty-selection guard, live restart, unselect-last-app auto-stop, process-death recovery, establish() failure path |
| **Total automated** | **15** | | |

`InstalledAppsProvider` (the `PackageManager` glue) and the Compose UI (`AppListScreen`,
`MainActivity`) are not unit tested — they have no branching logic of their own once the pure
filter and the ViewModel are covered; correctness is verified by running the app (see
`docs/manual-testing.md`).

**Why `vpn/` stays manual**: `VpnService.Builder.establish()` requires a real (or emulated)
Android VPN subsystem — Robolectric doesn't shadow it meaningfully, and there's no fake to
substitute without testing a fake instead of the real mechanic. Same trade-off Raven documents
for `OverlayService`'s `WindowManager` calls.

---

## Directory Structure

```
app/src/test/java/app/anglerfish/
├── data/
│   ├── AppListFilterTest.kt
│   └── DataStoreAppRepositoryTest.kt
└── ui/
    └── AppListViewModelTest.kt
```

No `integration-mock`/`integration-real`/`androidTest` tiers exist yet — the MVP's only
Android-framework-dependent code (`InstalledAppsProvider`, `vpn/`, Compose UI) is covered by the
manual checklist instead, per the trade-off above. Add an instrumented tier if UI complexity
grows enough to justify it.

---

## Run Commands

```bash
./gradlew testDebugUnitTest                                  # all unit tests
./gradlew testDebugUnitTest --tests "app.anglerfish.data.*"  # data/ package only
./gradlew testDebugUnitTest --tests "app.anglerfish.ui.*"    # ui/ package only
```
