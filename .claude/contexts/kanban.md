# Kanban - Anglerfish

Track of work sessions and completed tasks linked to GitHub issues.

---

2026-09-28 - [#20] Fix: app list only showed a handful of OS-exempted apps on real devices
- Root cause: Android 11+ package visibility — `queryIntentActivities()` silently returns only a
  small OS-exempted set without a `<queries>` declaration; the user's actual installed apps
  (browser, social, games) were invisible to the query
- Fix: added `<queries><intent>` for `ACTION_MAIN`/`CATEGORY_LAUNCHER` to `AndroidManifest.xml`
  — the one query Android explicitly supports without the heavier, Play-Store-review-gated
  `QUERY_ALL_PACKAGES` permission
- No change to `filterUserLaunchableApps`/`isPureSystemApp` — that logic was correct all along,
  the apps just never reached it
tags: #bug #manifest #package-visibility
Ref: https://github.com/TomasGC/Anglerfish/issues/20

---

2026-09-26 - [#19] Python dev-tooling (manage.py) and CI/CD, ported from Raven/Otter
- `scripts/`: Python CLI (`manage.py build/test/validate/coverage/adb`) ported from Raven,
  adapted for a single-module project (no `-DtestType` tier filter yet)
- Kover wired in `app/build.gradle.kts` — report generation only, no hard threshold gate
  (`coverage-threshold: 0` in CI, interim — Raven's default 80 only works there because it has
  ~95% real coverage)
- Detekt wired in with Composable-aware overrides (`FunctionNaming`, `LongParameterList`) and a
  raised `ReturnCount` limit for guard-clause style; 3 real findings fixed in app code
- `.github/workflows/push-ci.yml` + `pr-ci.yml` via `TomasGC/condor`'s reusable workflows;
  `.osv-scanner.toml` copied from Raven (same AGP/Gradle-internal build-tool dependency overrides
  apply — identical version family)
- Two real bugs found and fixed along the way: `gradlew`'s wrapper checksum was byte-for-byte
  correct but uppercase (Gradle's comparison is case-sensitive, fails 100% of the time), and
  `gradlew` had lost its executable bit
- `gradle/verification-metadata.xml` regenerated to cover both Windows and Linux
  platform-specific artifacts (`aapt2`) plus Detekt/Lint/Kover's own tool dependencies —
  `--write-verification-metadata` is additive, so running it again on a different OS adds that
  OS's entries without needing a hand merge
tags: #ci #python #detekt #kover #tooling
Ref: https://github.com/TomasGC/Anglerfish/issues/19
PR: https://github.com/TomasGC/Anglerfish/pull/21

---

2026-09-21 - [#9] Docs refresh: README, .claude/, wiki
- Original issue #9 scope (commit `.claude/` to git) already happened as a side effect of the
  per-issue docs-commit convention started at #5 — this issue became a final wrap-up pass instead
- `tests.md` counts confirmed real (not "target"): 4+4+7 = 15 unit tests, verified via
  `./gradlew testDebugUnitTest` JUnit XML reports
- Added root `README.md` (project overview, build instructions, doc links) — didn't exist before
- Fixed 3 wiki pages that still referenced the pre-renumbering issue scheme and wrongly claimed
  `.claude/` was gitignored
tags: #docs #wrap-up
Ref: https://github.com/TomasGC/Anglerfish/issues/9
PR: https://github.com/TomasGC/Anglerfish/pull/18
Commit: 8f88916

---

2026-09-21 - [#8] Manual VpnService verification checklist
- `docs/manual-testing.md`: 8 on-device scenarios (first-run consent, activate/deactivate,
  notification deactivate action, empty-selection guard, live restart on selection change,
  unselect-last-app auto-stop, process-death recovery, establish() failure path)
- Docs-only, no code — the substitute for automated coverage of `vpn/`, which
  `VpnService.Builder.establish()` makes impossible to unit test
- Correction: `.claude/contexts/conventions.md` clarified that `docs:` commits never carry an
  issue number, not even in prose — the controller initially got this wrong by reasoning from
  Raven's real (inconsistent) git history instead of Anglerfish's own stated convention
tags: #docs #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/8
PR: https://github.com/TomasGC/Anglerfish/pull/17
Commit: 0885f82

---

2026-09-21 - [#7] Compose UI wiring
- `AppListScreen`: top-bar Activate/Deactivate switch, `LazyColumn` of apps with checkboxes,
  snackbar for the empty-selection guard
- `MainActivity` fully replaced (was #1's placeholder): builds the ViewModel via
  `AnglerfishApplication.container`, wires the VPN consent `ActivityResultLauncher` (on
  `RESULT_OK` calls `onConsentGranted()` directly — never re-checks consent by calling
  `onActivateClicked()` again, which would lose the original activation intent), requests the
  Android 13+ notification permission before activating (non-blocking on denial)
- App is now feature-complete for v1's core loop: browse, select, activate/deactivate, persist
  across restart, foreground notification
tags: #ui #compose #consent-flow
Ref: https://github.com/TomasGC/Anglerfish/issues/7
PR: https://github.com/TomasGC/Anglerfish/pull/16
Commit: 8dfb78d

---

2026-09-21 - [#6] AppListViewModel
- `AppListUiState`/`AppListItem`/`AppListEvent` (`SelectionEmpty`, `VpnConsentRequired`) +
  `AppListViewModel`: `toggleApp`, `onActivateClicked`, `onConsentGranted`, `onDeactivateClicked`
- Empty-selection guard on activate; consent-needed branch defers start to a separate
  `onConsentGranted()` call; unselecting the last active app stops instead of restarting with an
  empty set; toggling while active restarts with the full current selection (not just the
  toggled package)
- 7 unit tests against `FakeAppRepository`/`FakeVpnGateway`, no real `Context`/`VpnService`/
  DataStore needed
- Task review caught a real gap: the "toggle while active" test's original single-app fixture
  couldn't distinguish "restart with full set" from a hypothetical "restart with just the
  toggled package" bug — fixed by strengthening the fixture to 2 apps (see the SDD ledger)
tags: #viewmodel #mvvm #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/6
PR: https://github.com/TomasGC/Anglerfish/pull/15
Commits: 04e7a42, 34b131e

---

2026-09-21 - [#5] AnglerfishVpnService mechanic
- `AnglerfishVpnService`: `Builder().addAllowedApplication(pkg)` per selected package,
  `establish()`, background drain thread discards every packet with nothing written back;
  rebuilds the tunnel from the current selection on every start (supports auto-restart on
  selection change while active)
- Foreground notification (with Deactivate action) + `START_STICKY`; `establish()` failure and
  `onRevoke()` both reset `AppRepository`'s active flag and surface a toast, never crash
- `AppContainer` (manual DI) + the real `AnglerfishApplication` that owns it, replacing #1's
  placeholder
- Adapted from a read-only reference in Raven (`app.raven.vpn.AdBlockVpnService`) — no Raven
  code imported; the allow-list is rebuilt from the current selection every start, not
  hardcoded to one package
- Not unit tested (`VpnService.Builder.establish()` needs a real/emulated device) — manual
  verification checklist lands in #8
tags: #vpn #mechanic #foreground-service
Ref: https://github.com/TomasGC/Anglerfish/issues/5
PR: https://github.com/TomasGC/Anglerfish/pull/14
Commit: e54b56a

---

2026-09-21 - [#4] VPN gateway seam
- `vpnConsentIntent()`, `VpnGateway` interface, `VpnController` — real `Context`-based
  implementation
- `VpnGateway` exists purely so #6's ViewModel can be unit-tested without a real
  `Context`/`VpnService`; includes a compile-only `AnglerfishVpnService` stub until #5 replaces it
tags: #vpn #testability-seam
Ref: https://github.com/TomasGC/Anglerfish/issues/4
PR: https://github.com/TomasGC/Anglerfish/pull/13
Commit: 62c0cb8

---

2026-09-21 - [#3] Persistence layer
- `InstalledAppsProvider` (`PackageManager` glue, not unit tested), `BlockingState`,
  `AppRepository` interface, `DataStoreAppRepository` (Preferences DataStore)
- DataStore tested purely on the JVM via `PreferenceDataStoreFactory` + `TemporaryFolder` — no
  device/Robolectric needed
tags: #data #datastore #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/3
PR: https://github.com/TomasGC/Anglerfish/pull/12
Commit: 283cdc1

---

2026-09-21 - [#2] App-list domain model and filtering
- `InstalledApp` domain model, `AppCandidate` + pure `filterUserLaunchableApps` (excludes pure
  system apps and Anglerfish itself)
- 4 unit tests, no `PackageManager` dependency in this code (kept pure for testability)
tags: #data #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/2
PR: https://github.com/TomasGC/Anglerfish/pull/11
Commit: d344540

---

2026-09-21 - [#1] Project scaffolding
- Kotlin + Compose skeleton, package `app.anglerfish`, minSdk 26 / target+compile SDK 36, Gradle
  version catalog, MVVM package layout
- Gradle wrapper regenerated from the official distribution with checksum verification (found
  during task review — the first-drafted wrapper jar had no provenance verification)
tags: #scaffolding #gradle
Ref: https://github.com/TomasGC/Anglerfish/issues/1
PR: https://github.com/TomasGC/Anglerfish/pull/10
Commits: 3d609ee, 0d520f3
