# Architecture - Anglerfish

**Last Updated**: 2026-09-21

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

Three Gradle modules (#53): `:core` is a plain JVM library with the pure logic (repositories,
parsing, DNS and NAT engines), so its tests run without Android. `:app` is the Android app: Compose
UI, ViewModel, `VpnService`, DI and the PackageManager/ConnectivityManager glue. `:app-instrumented`
is a `com.android.test` module that runs the on-device tests against `:app`. `:core` depends on nothing
in `:app`, so there is no cycle and no solver-module split is needed.

### Directory Layout

```
Anglerfish/
├── app/src/
│   ├── main/java/app/anglerfish/
│   │   ├── AnglerfishApplication.kt
│   │   ├── di/AppContainer.kt
│   │   ├── data/                  # InstalledAppsProvider (PackageManager glue)
│   │   ├── dns/                   # Android glue: ConnectivityManager resolver, UDP forwarder
│   │   ├── vpn/                   # VpnGateway/VpnController/AnglerfishVpnService
│   │   └── ui/                    # Compose screen, ViewModel, MainActivity
│   └── test/java/app/anglerfish/  # Android unit tests — see contexts/tests.md
├── core/src/
│   ├── main/java/app/anglerfish/  # pure logic: data/, dns/, nat/
│   ├── test/                      # unit tests
│   ├── integrationMock/           # mocked-boundary tests
│   └── integrationReal/           # real DataStore file I/O
└── app-instrumented/src/main/java/app/anglerfish/e2e/  # on-device tests (ActivationFlowTest)
```

---

## Key Decisions

- **No Hilt**: app is 1 screen/1 service/1 repo, manual constructor wiring via a single
  `AppContainer` is simpler and has no build-time codegen cost.
- **Selection changes while blocking is active auto-restart the tunnel** (see the design spec)
  rather than requiring manual deactivate/reactivate — favors a simple mental model ("the switch
  always reflects the current selection") over avoiding a brief reconnect blip.
- **`VpnGateway` interface** exists solely so `AppListViewModel` is unit-testable without a real
  `Context`/`VpnService`.
- **App-launch reconciliation covers process-death and reboot recovery** — `AppListViewModel`
  restarts the tunnel from the persisted selection as soon as it's constructed, whenever the
  persisted `isActive` flag says blocking should be on. `START_STICKY` alone only survives a soft
  kill (`adb shell am kill`, low-memory reclaim); a hard kill (force-stop, swiping the app from
  recents) or a full reboot both bypass it, leaving a tunnel-less "active" switch until the user
  reopens the app — which this reconciliation now covers unconditionally, without needing the
  user to manually toggle off and back on (see issue #25).

---

## Toolchain Relationship to Raven/Otter

The `.claude/` documentation structure (this file and its siblings) deliberately mirrors the
sibling Android projects Raven (`C:\dev\repos\GitHub\Raven`) and Otter (`C:\dev\repos\GitHub\otter`)
for consistency across the user's own Android projects: commit format (`#XXX: type: description`),
branch naming, package convention (`app.<name>`, not `com.tomasgc.*`), and this six-file
`contexts/` layout. Where Raven/Otter's own apparatus is disproportionate to Anglerfish's actual
size — Hilt, the `:core`/solver-module split, the
unit/integration-mock/integration-real/instrumented four-tier test structure — Anglerfish adopts
the *pattern* (documented decisions, a manual-DI seam, a pure-logic/Android-glue split, a unit +
manual-checklist test split) at a scope appropriate to a one-module, one-screen app, rather than
copying the scale wholesale.

Issue #19 ported Raven's `scripts/` Python dev-tooling (`manage.py build/test/validate/coverage`,
see `contexts/commands.md`), Detekt, Kover, and both CI workflows (`push-ci.yml`/`pr-ci.yml`, via
`TomasGC/condor`'s reusable workflows) wholesale rather than partially — unlike Hilt or a
multi-module split, none of this tooling scales with app size, so there was no proportionality
argument against adopting it now. `coverage-threshold: 0` in `push-ci.yml` and no `verify{}` gate
in Gradle are deliberate interim choices, not oversights — see `contexts/design-patterns.md`.
