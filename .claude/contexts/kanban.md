# Kanban - Anglerfish

**Last Updated**: 2026-09-28

---

## Project Status

- Issues #1-#9 complete: v1 core loop shipped (browse, select, activate/deactivate, persist,
  notification). One branch per issue off up-to-date `main`, one PR per issue, each branch's
  last commit updates `.claude/CLAUDE.md` + `contexts/*.md` (committed to git — only
  `.claude/sessions/` stays gitignored).
- Design spec/plan renumbered issues #1-#9 to match the plan's Task N exactly (2026-09-21) —
  see the plan's Global Constraints and the SDD ledger under `.superpowers/sdd/` for why.
- Issue #19 (Python dev-tooling + CI/CD, ported from Raven) in progress: Push-CI green on the
  branch, PR not yet opened.

---

## Backlog

**High priority**
- Issue #19: open the PR, confirm PR-CI also goes green (untestable pre-PR — `workflow_run`
  only fires once a PR exists).

**Medium priority**
- Manual on-device run-through of the full `docs/manual-testing.md` checklist (a real
  wireless-ADB install during #19's `manage.py build` verification confirmed install + launch —
  the 8-scenario walkthrough itself hasn't been done end-to-end).

**Low priority**
- Search/filter/categories/bulk-select on the app list — explicitly out of scope for v1.
- Device-reboot recovery — explicitly out of scope for v1.

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
  issue prefix, no exceptions — the controller initially got this wrong by reasoning from
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

---

## Ideas

- Coverage threshold: raise `coverage-threshold` in `push-ci.yml` above 0 once there's enough
  real coverage for a number to mean something (see issue #19).
- Device-reboot recovery, app-list search/filter — see Backlog's Low priority for the full list.

---

## Related Documentation

- `.claude/CLAUDE.md` - Project instructions
- `.claude/contexts/architecture.md` - Module layout, internal app flow
- `.claude/contexts/design-patterns.md` - Patterns in use and why
- `.claude/contexts/tests.md` - Test counts and structure
- `.claude/sessions/specs/` - Point-in-time design specs (gitignored, local only)
- `.claude/sessions/plans/` - Point-in-time SDD implementation plans (gitignored, local only)
- GitHub: https://github.com/TomasGC/Anglerfish/issues,
  https://github.com/users/TomasGC/projects/10/views/1,
  https://github.com/TomasGC/Anglerfish/wiki
