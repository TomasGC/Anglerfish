# Kanban - Anglerfish

**Last Updated**: 2026-09-21

---

## Project Status

- 9 GitHub issues, each = exactly one implementation-plan task, in dependency order: #1
  scaffolding, #2 domain model/filter, #3 persistence layer, #4 VpnGateway/Controller, #5
  VpnService mechanic, #6 ViewModel, #7 Compose UI, #8 manual test checklist, #9 (folded into
  the per-issue docs-commit convention below — see note).
- One branch per issue, off up-to-date `main`, one PR per issue, merged before the next issue's
  branch is created (issues #1-#4 done this way). Each branch's last commit updates
  `.claude/CLAUDE.md` + `contexts/*.md` + `settings.json` (this file and its siblings) to
  reflect what actually landed — matching Raven/Otter's convention, and committed to git (only
  `.claude/sessions/` stays gitignored; this was originally set up wrong — `.claude/` was
  gitignored entirely until this note).
- Issues #1-#4 merged: project skeleton, app-list domain model + filter, DataStore persistence
  + `InstalledAppsProvider`, `VpnGateway`/`VpnController` seam (with a compile-only
  `AnglerfishVpnService` stub, replaced by #5).
- Design spec (`.claude/sessions/specs/2026-09-18-anglerfish-design.md`) and implementation plan
  (`.claude/sessions/plans/2026-09-18-anglerfish-mvp.md`) — the plan's "Task N" now equals
  issue #N exactly (renumbered 2026-09-21; originally split into 4 product-POV + 5
  technical-POV issues describing the same 9 units of work twice, which couldn't each get an
  independent sequential PR — see the plan's Global Constraints and the SDD ledger under
  `.superpowers/sdd/` for the full history of that correction).

---

## Backlog

**High priority**
- Issues #6-#9, in order, per the implementation plan.

**Ideas**
- Search/filter/categories/bulk-select on the app list — explicitly out of scope for v1.
- Device-reboot recovery — explicitly out of scope for v1.

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

(see Backlog above for near-term; nothing longer-term yet)

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
