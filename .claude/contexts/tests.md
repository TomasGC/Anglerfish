# Tests - Anglerfish

**Last Updated**: 2026-10-01

---

## Counts (verified via `python scripts/manage.py test`, JUnit XML reports)

| Category | Tests | Runner | Description |
|----------|-------|--------|--------------|
| Unit (`data/`) | 4 + 16 + 15 | JUnit + kotlinx-coroutines-test | `AppListFilterTest` (pure filter logic), `DataStoreAppRepositoryTest` (JVM-only DataStore via `PreferenceDataStoreFactory` + `TemporaryFolder` — selection/active persistence incl. per-app `BlockMode` and its legacy/corrupt-entry migration fallback, `setMode` upsert + unhide semantics, layout default and persistence, hidden-package persistence and its mutual exclusion with selection, including a two-package partial-deselect case), `BlocklistParsingTest` (pure: hosts-file parsing restricted to `0.0.0.0`-sinked lines with a real domain token, lowercased; suffix-aware domain matching with case/trailing-dot normalization and false-positive avoidance) |
| Unit (`ui/`) | 23 + 5 | JUnit + kotlinx-coroutines-test | `AppListViewModelTest` against `FakeAppRepository`/`FakeVpnGateway` — empty-selection guard, consent-needed branch, activate/deactivate, auto-restart and auto-stop on selection change, tunnel reconciliation on construction when persisted state is active, search-query filtering (match, clear, selection while filtered, filters within all three buckets), layout toggling (reflected in uiState, persisted, reversible, respects the active search filter), hide/unhide bucket placement and its VPN-restart interaction (stops when it was the last selection, restarts with the remaining selection, no-ops when hiding a not-selected app, ignores a stale active flag read before its own state change); `BlocklistViewModelTest` against a `FakeBlocklistRepository` — add/remove pass-through, blank-input guard, trim + lowercase normalization |
| Contract (`data/`) | 6 | JUnit + kotlinx-coroutines-test | `AppRepositoryContractTest` (abstract, 3 assertions: default mode on select, `setMode` unhides, hiding deselects) run against both `FakeAppRepositoryContractTest` and `DataStoreAppRepositoryContractTest` — guarantees `FakeAppRepository` (used throughout `AppListViewModelTest`) can't silently drift from `DataStoreAppRepository`'s real behavior the way it did twice in one session while building per-app `BlockMode`. No `ViewModel`, no `Dispatchers.Main` substitution — direct `runTest {}` + suspend calls, same proven pattern as `DataStoreAppRepositoryTest` |
| Repository (`data/`) | 11 | JUnit + kotlinx-coroutines-test | `DataStoreBlocklistRepositoryTest` — merges remote-cache/user-addition layers (no bundled layer — see below), returns false for everything before any fetch has ever happened, `refreshIfStale`'s interval/success/failure/empty-parse-rejection branches via a `FakeBlocklistFetcher` |
| Manual (`vpn/`) | 8 scenarios | On-device checklist | `docs/manual-testing.md` — first-run consent, activate/deactivate, notification deactivate action, empty-selection guard, live restart, unselect-last-app auto-stop, process-death recovery (soft kill + hard kill + reconciliation), establish() failure path |
| **Total automated** | **80** | | |

**No bundled blocklist asset**: the original design bundled a snapshot of the full StevenBlack/hosts
list as an app asset so first-launch-before-any-fetch still blocked something. That 2.2MB asset
tripped the shared CI large-file gate (500KB cap, even gzip-compressed) — dropped entirely rather
than trimmed, since `AnglerfishApplication.onCreate()` already calls `refreshIfStale()`
unconditionally and `lastFetch` defaults to 0, so first launch always starts a real fetch
immediately. See `contexts/design-patterns.md`'s "No Bundled Snapshot" entry.

**Known deferred performance item (flagged for issue #40)**: `DataStoreBlocklistRepository.isBlocked`
re-parses the cached remote text and rebuilds the full bundled+remote+additions union on every
call — harmless today since nothing calls it live (#39's own scope explicitly ends before any
consumer exists), but #40's DNS interception layer will call it per query. The right caching
strategy (a `StateFlow`-backed merged set, invalidated on DataStore change) was deliberately left
for #40 to design alongside its actual call pattern, not guessed at here.

`InstalledAppsProvider` (the `PackageManager` glue) and the Compose UI (`AppListScreen`,
`MainActivity`) are not unit tested — they have no branching logic of their own once the pure
filter and the ViewModel are covered; correctness is verified by running the app (see
`docs/manual-testing.md`).

**Why `vpn/` stays manual**: `VpnService.Builder.establish()` requires a real (or emulated)
Android VPN subsystem — Robolectric doesn't shadow it meaningfully, and there's no fake to
substitute without testing a fake instead of the real mechanic. Same trade-off Raven documents
for `OverlayService`'s `WindowManager` calls.

**Why no `ViewModel` + real-repository integration test**: tried during issue #38 and reverted.
`viewModelScope` is fire-and-forget with no public way to await or cancel it from test code, which
made a bare JVM test (no Robolectric, no device) genuinely flaky — `Dispatchers.setMain()` is a
trick built for fake/synchronous dependencies, and pairing it with a real async one surfaced a real
reentrancy hang (`Dispatchers.Unconfined` resuming inline on DataStore's own actor thread) and a
cross-test leak (an orphaned coroutine throwing into an unrelated test once `TemporaryFolder`
deleted its backing file). Issue #27's `integration-mock` tier (Robolectric, not yet built) is the
right place for ViewModel-plus-real-dependency testing — Robolectric's `ShadowLooper.idle()` gives
a real drain signal a bare JVM test doesn't have. The contract test above gets the actual value this
was chasing (Fake/real parity) without needing a ViewModel in the loop at all.

---

## Directory Structure

```
app/src/test/java/app/anglerfish/
├── data/
│   ├── AppListFilterTest.kt
│   ├── DataStoreAppRepositoryTest.kt
│   ├── AppRepositoryContractTest.kt          # abstract, shared assertions
│   ├── FakeAppRepositoryContractTest.kt      # subclass: FakeAppRepository
│   └── DataStoreAppRepositoryContractTest.kt # subclass: real DataStoreAppRepository
└── ui/
    ├── AppListViewModelTest.kt
    └── FakeAppRepository.kt   # shared: AppListViewModelTest + the contract test's Fake subclass
```

`integration-mock` (Robolectric) and `androidTest` (instrumented) tiers from issue #27's design
spec (`.claude/sessions/specs/2026-09-29-test-tier-buildout-design.md`) are still unbuilt. The
MVP's Android-framework-dependent code (`InstalledAppsProvider`, `vpn/`, Compose UI) stays on the
manual checklist until that lands.

---

## Run Commands

```bash
python scripts/manage.py test       # all tests, all tiers -- no -DtestType/package filter yet (issue #27)
python scripts/manage.py coverage   # same, plus a Kover line-coverage percentage
```
