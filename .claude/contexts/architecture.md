# Architecture - Anglerfish

**Last Updated**: 2026-09-18

---

## The mechanic

`VpnService.Builder().addAllowedApplication(pkg)` once per currently-selected package, then
`establish()`. A background thread reads and discards every packet with nothing written back —
selected apps' network calls time out exactly like "no internet". No packet parsing/forwarding,
no real VPN protocol or server.

Adapted from a read-only reference in Raven (`app.raven.vpn.AdBlockVpnService`) — no Raven code
imported. Difference: the allow-list is rebuilt from the current selection on every service
start, not hardcoded to one package.

---

## `:app` Internal Layout

```
AppListScreen ──setContent──> MainActivity
     │                              │
     │ onToggleApp/onActivateClicked│ onConsentRequired
     ▼                              ▼
AppListViewModel <───────────── AppContainer (AppListViewModelFactory)
     │  toggleApp / onActivateClicked / onConsentGranted / onDeactivateClicked
     ▼
AppRepository (DataStore)  ◄────────────┐
     │  state: Flow<BlockingState>      │ setActive(false) on failure/onRevoke
     ▼                                  │
VpnGateway (VpnController) ──Intent──> AnglerfishVpnService
                                          ├─ builds tunnel from selected packages
                                          ├─ drain thread discards packets
                                          └─ foreground notification + Deactivate action
```

`filterUserLaunchableApps` and `AppListViewModel`'s branching are extracted as pure/injectable
Kotlin, specifically so the app's one genuinely-easy-to-get-wrong logic (system/self exclusion,
activate/deactivate/toggle state transitions) is unit-testable without Robolectric or a device.
`InstalledAppsProvider` and `AnglerfishVpnService` are thin compositions of that logic plus
Android framework calls (`PackageManager`, `VpnService.Builder`) verified manually on a device —
a deliberate, documented trade-off (see `contexts/tests.md`), not a coverage gap.

---

## Module Structure

Single Gradle module (`:app`) — no multi-module split. Raven's `:core`/solver-module structure
exists specifically to prevent a real circular dependency (a solver module needs the `PuzzleModule`
contract, `:app` needs to list every solver module). Anglerfish has no such cycle: one screen, one
service, one repository, all in one module, organized by package (`data/`, `vpn/`, `ui/`, `di/`)
instead.

---

## Key Decisions

- **No Hilt**: app is 1 screen/1 service/1 repo, manual constructor wiring via a single
  `AppContainer` is simpler and has no build-time codegen cost.
- **Selection changes while blocking is active auto-restart the tunnel** (see the design spec)
  rather than requiring manual deactivate/reactivate — favors a simple mental model ("the switch
  always reflects the current selection") over avoiding a brief reconnect blip.
- **`VpnGateway` interface** exists solely so `AppListViewModel` is unit-testable without a real
  `Context`/`VpnService`.
- **No device-reboot recovery in v1** — `START_STICKY` covers process-death recovery only; a
  full reboot requires the user to reactivate manually.

---

## Toolchain Relationship to Raven/Otter

The `.claude/` documentation structure (this file and its siblings) deliberately mirrors the
sibling Android projects Raven (`C:\dev\repos\GitHub\Raven`) and Otter (`C:\dev\repos\GitHub\otter`)
for consistency across the user's own Android projects: commit format (`#XXX: type: description`),
branch naming, package convention (`app.<name>`, not `com.tomasgc.*`), and this six-file
`contexts/` layout. Where Raven/Otter's own apparatus is disproportionate to Anglerfish's actual
size — Hilt, the `:core`/solver-module split, the Detekt+Kover coverage gate, the
unit/integration-mock/integration-real/instrumented four-tier test structure — Anglerfish adopts
the *pattern* (documented decisions, a manual-DI seam, a pure-logic/Android-glue split, a unit +
manual-checklist test split) at a scope appropriate to a one-module, one-screen app, rather than
copying the scale wholesale.
