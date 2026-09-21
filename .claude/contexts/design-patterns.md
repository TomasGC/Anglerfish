# Design Patterns - Anglerfish

**Last Updated**: 2026-09-18

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

---

## Patterns Deliberately Not Yet In Use

- **Hilt** — Raven and Otter both use Hilt; Anglerfish doesn't. One screen, one service, one
  repository is small enough that a hand-written `AppContainer` is less code and less build time
  than Hilt's codegen. Revisit only if the app's scope grows meaningfully beyond the MVP.
- **Multi-module Gradle split** — Raven's `:core`/solver-module structure exists to prevent a
  real circular dependency (solver modules need the contract, `:app` needs the solver modules).
  Anglerfish has no such cycle: everything lives in `:app`, split into packages, not modules.
- **Detekt/Kover coverage gate** — no static-analysis/coverage-threshold tooling wired in for
  the MVP; add it if the codebase grows enough that the sibling projects' 80% gate becomes worth
  the setup cost here too.
- **Event bus between `AnglerfishVpnService` and `AppListViewModel`** — the service corrects
  `AppRepository` directly on failure instead of signaling the ViewModel through a separate
  channel (see "Repository as Single Source of Truth" above) — simpler, and the UI already
  observes the repository reactively.
