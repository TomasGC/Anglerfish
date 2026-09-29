# Design Patterns - Anglerfish

**Last Updated**: 2026-09-21

---

## Patterns In Use

### Testability Seam (`VpnGateway` interface)

`VpnGateway` (`needsConsent`/`start`/`restart`/`stop`) sits between `AppListViewModel` and the
real `Context`-based `VpnController`. The ViewModel depends only on the interface, so its tests
use a `FakeVpnGateway` instead of a real `Context` or a running `VpnService`. Introduced because
the ViewModel's activate/deactivate/toggle branching (empty-selection guard, consent-needed
branch, auto-restart-on-change) is exactly the logic worth unit-testing — the interface earns its
keep here, unlike a speculative abstraction added "in case it's needed later."

### Repository as Single Source of Truth

`AppRepository.state: Flow<BlockingState>` is the only place selection + active-flag live. The UI
observes it, the ViewModel mutates it, and `AnglerfishVpnService` corrects it directly (via the
same `AppContainer`) on `establish()` failure or `onRevoke()` — so the "is blocking active" switch
in the UI can never drift from what the VpnService actually did, without a separate event bus
between the service and the ViewModel.

### Pure Filter Function, Android Glue Kept Separate

`filterUserLaunchableApps(candidates: List<AppCandidate>, selfPackageName: String)` takes plain
data and returns plain data — no `PackageManager` dependency. `InstalledAppsProvider` is the thin,
untested glue that turns real `ResolveInfo`/`ApplicationInfo` into `AppCandidate` and calls the
pure function. Same split as Raven's `OverlayButtonGestureDetector` vs. `OverlayService`: keep the
one genuinely-easy-to-get-wrong piece of logic (which apps count as "system") unit-testable in
isolation, and let the untestable Android-framework call sit in a file with no branching logic to
verify.

### Sealed Interface for One-Shot UI Events

`AppListEvent` (`SelectionEmpty` / `VpnConsentRequired`) models a one-shot side effect the
ViewModel wants the UI to perform, carried through a buffered `Channel`/`Flow` rather than a
`StateFlow`, so a rotation/recomposition can't re-fire an already-consumed event.

### Reconcile Persisted State Against Reality on Construction

`AppListViewModel`'s `init` block restarts the tunnel whenever the persisted `isActive` flag is
true, unconditionally — it doesn't ask *why* the ViewModel is being constructed fresh (first
launch after a hard kill, after a reboot, after a reinstall that preserved DataStore). Persisted
state and real system state (the actual `VpnService`) can only drift apart while nothing is
observing both at once; the fix isn't to catch every way they can drift, it's to always
re-derive the real state from the persisted one at the one moment a fresh ViewModel is
guaranteed to run. Reuses `vpnGateway.restart()`, the exact same call `toggleApp` already makes
for a live selection change — no new interface method needed.

### Backoff-and-Retry on an Anomalous Zero-Length Blocking Read

`AnglerfishVpnService.drain()` treats `input.read(buffer) == 0` as "nothing queued right now,"
not as EOF — it sleeps briefly and retries, rather than breaking the loop. A blocking read on a
tun fd has no standard reason to return exactly `0` (only a full packet or `-1` on close), but
on-device testing found it happens on this hardware even on a pure read-only drain with nothing
ever written back — a device/driver-level quirk, not application-triggered. Real traffic was
confirmed to keep arriving normally seconds later, so treating it as fatal (breaking the loop)
would kill the drain thread and eventually silence the tunnel's discard loop for no real reason.
A short sleep-and-retry self-heals when it's transient and stays CPU-cheap even if it were ever
sustained, unlike either busy-spinning on the syscall or giving up on the thread.

---

## Patterns Deliberately Not Yet In Use

- **Hilt** — Raven and Otter both use Hilt; Anglerfish doesn't. One screen, one service, one
  repository is small enough that a hand-written `AppContainer` is less code and less build time
  than Hilt's codegen. Revisit only if the app's scope grows meaningfully beyond the MVP.
- **Multi-module Gradle split** — Raven's `:core`/solver-module structure exists to prevent a
  real circular dependency (solver modules need the contract, `:app` needs the solver modules).
  Anglerfish has no such cycle: everything lives in `:app`, split into packages, not modules.
- **Kover/Detekt coverage *threshold* gate** — Kover and Detekt are both wired in (issue #19:
  `./gradlew koverXmlReportDebug detekt`, `python scripts/manage.py coverage`), but with no
  `verify { rule { minBound(80) } }` block in `app/build.gradle.kts` and `coverage-threshold: 0`
  in `push-ci.yml` — Gradle/CI never fail a build over a specific coverage percentage yet.
  `CoverageAction`'s own Python-side 80% check is a soft report/warning only. Whether to add a
  real hard gate (and at what threshold) is deferred until there's enough coverage for a number
  to mean anything — raise `coverage-threshold` once that's true.
- **Event bus between `AnglerfishVpnService` and `AppListViewModel`** — the service corrects
  `AppRepository` directly on failure instead of signaling the ViewModel through a separate
  channel (see "Repository as Single Source of Truth" above) — simpler, and the UI already
  observes the repository reactively.

---

## Patterns In Use (added in issue #19)

### Cross-Platform Gradle Dependency-Verification Regeneration

`gradle/verification-metadata.xml` records a checksum per resolved artifact; Gradle refuses to
build if a downloaded artifact doesn't match. The catch: some artifacts (`aapt2`, Android's build
tool) ship separate platform-specific jars (`-windows.jar`, `-linux.jar`), so a file generated on
one OS is missing the other platform's entries. `./gradlew --write-verification-metadata sha256`
is **additive** — running it again on a different OS adds that OS's entries without touching
what's already recorded, rather than starting over. The practical recipe when CI reports
"Dependency verification failed" for a platform-specific artifact: regenerate on that platform
(a throwaway `push`-triggered GitHub Actions job that uploads the resulting file as a build
artifact works when you don't have that OS locally — `workflow_dispatch` won't work for a
workflow that only exists on a feature branch, GitHub requires dispatchable workflows to already
be on the default branch), download the result, and use it directly — don't hand-merge, the file
already contains your platform's prior entries too.

### Case-Sensitive Wrapper Checksum Comparison

`gradle-wrapper.properties`' `distributionSha256Sum` is compared as a literal string against the
freshly-computed (always-lowercase) hash — not case-insensitively. A checksum that is byte-for-byte
correct except for letter case fails with "Verification of Gradle distribution failed!", the exact
same message a genuinely wrong or tampered checksum produces. Always lowercase the value.

### Composable-Aware Detekt Overrides

`config/detekt/detekt.yml` relaxes `FunctionNaming` (PascalCase) and `LongParameterList` (one
parameter per callback/state slot is normal Compose shape, not a smell) for `@Composable`-annotated
functions specifically, via each rule's `ignoreAnnotated` list — not a blanket suppression, not a
baseline file grandfathering in violations. `ReturnCount`'s default limit of 2 is raised to 4
project-wide, since early-return guard clauses (see `AnglerfishVpnService.onStartCommand`) are the
preferred style here over nested conditionals.
