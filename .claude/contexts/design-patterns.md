# Design Patterns - Anglerfish

**Last Updated**: 2026-09-30

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

## Patterns In Use (added in issue #33)

### Opaque Foreground Content Inside `SwipeToDismissBox`

`SwipeToDismissBox` only hides `backgroundContent` where the foreground `content` slot actually
paints pixels — it does not clip or mask the background to the foreground's outline. `AppRow`/
`AppGridItem` have no `background()` of their own, so the "Hide" label was visible at rest on
every row and cell, not just mid-swipe: any layout gap (padding, border area, empty space beside a
short label) let the pink background bleed through. Found on-device, not by the unit suite (Compose
UI has no automated tier here). Fixed once, at the wrapper (`SwipeToHideBox` wraps `content` in
`Box(Modifier.background(MaterialTheme.colorScheme.surface))`), rather than adding a background to
every row/cell composable individually — the standard Material3 usage pattern for this composable,
skipped in the first pass.

## Patterns In Use (added in issue #39)

### No Bundled Snapshot — First Launch Fetches Live

The design originally bundled a snapshot of the full StevenBlack/hosts list as a 2.2MB app asset,
so first-launch-before-any-fetch still blocked something. That asset tripped the shared CI
large-file gate (`condor`'s 500KB cap — even gzip-9 only got it to ~563KB, still over), and a
trimmed-down bundled subset would have meant maintaining a second, separate blocklist alongside
the real remote one. Dropped the bundled layer entirely instead:
`AnglerfishApplication.onCreate()` already calls `refreshIfStale()` unconditionally, and
`lastFetch` defaults to `0L`, so the very first app launch always starts a real fetch immediately
— no special-casing needed. The trade-off is explicit: blocking coverage is empty for the
seconds-to-minutes between first launch and that fetch completing, rather than instant-but-stale
from a committed snapshot. `DataStoreBlocklistRepository.isBlocked` now merges only the remote
cache and user additions.

### Reject a Fetched Payload That Fails to Parse

`refreshIfStale` only overwrites the 24h remote cache when `parseHostsFile(body).isNotEmpty()` —
an HTTP 200 with a captive-portal login page or truncated download parses to nothing, and treating
that as "fetch succeeded" would silently replace a day's worth of real ad-domain coverage with
zero coverage. Pairs with `parseHostsFile`'s own strict `0.0.0.0`-sink-only acceptance rule (see
below): the same strictness that rejects StevenBlack's own non-ad bootstrapping lines also doubles
as the signal that a response is garbage, with no separate "is this garbage" check needed.

### Sink-Address Gate, Not a Generic Shape Check

`parseHostsFile` requires the hosts-file line's sink address to literally be `"0.0.0.0"` and its
domain token to not *equal* the sink address — not merely "looks like a hostname" (e.g. "contains a
dot"), which an IP-address string also satisfies and would have let StevenBlack's own
self-referential `"0.0.0.0 0.0.0.0"` header line and its `127.0.0.1`/`::1` localhost-alias lines
through. Caught via TDD: a first attempt using a dot-presence filter didn't actually exclude
`"0.0.0.0"` (it contains dots too), and only reproducing the real header line as a test case
surfaced that the precise rule needed was identity-against-the-sink, not shape.

### Mutual Exclusion Enforced in One DataStore Transaction

An app can't be both selected for blocking and hidden from the list at once. `DataStoreAppRepository
.toggleHidden` clears the package from `SELECTED_PACKAGES_KEY` inside the same `dataStore.edit`
block that adds it to `HIDDEN_PACKAGES_KEY`, so the two fields can never be read in a briefly
inconsistent state by any observer of `state: Flow<BlockingState>`. Same shape as "Repository as
Single Source of Truth" above: the invariant lives at the one place that can enforce it atomically,
not as a rule the ViewModel or UI have to remember to uphold on every call site.
