# Project Instructions - Anglerfish

**Purpose**: Android per-app internet blocker instructions
**Last Updated**: 2026-09-21

---

## Project Context

@contexts/kanban.md
@contexts/architecture.md
@contexts/design-patterns.md
@contexts/commands.md
@contexts/conventions.md
@contexts/tests.md

---

## Hard Constraints (Non-Negotiable)

### Testing Requirements

**ALL TESTS MUST PASS** - No exceptions

After any code change:
1. Build: `./gradlew assembleDebug`
2. Test: `./gradlew testDebugUnitTest`
3. **If any test fails → BLOCK COMMIT**

`VpnService.Builder.establish()` cannot be exercised in a JVM unit test — `vpn/` is verified
manually per `docs/manual-testing.md`, not via automated tests. See `contexts/tests.md`.

---

### Version Control Rules

#### Commit Format

**Format**: `#XXX: type: description`

**Examples**:
```
#1: feat: add app-list domain model and system/self filtering
#6: feat: implement per-app VpnService black-hole tunnel
#3: fix: resolve selection not persisting across process death
```

**Branch naming**:
- Features: `feature/XXX-description`
- Bugfixes: `bugfix/XXX-description`

---

### Code Quality Standards

**Mandatory rules**:

1. **No hardcoded values** - constants or configuration only
2. **One class/interface per file** - single responsibility
3. **Strong typing** - avoid loose types, use sealed interfaces for state/events
4. **DRY principle** - no code duplication
5. **Immutability** - prefer `val` over `var`, use data classes
6. **Null safety** - leverage Kotlin's null-safety features
7. **Coroutines patterns** - structured concurrency (`viewModelScope`); the VPN drain loop is the
   one deliberate exception (needs a blocking read loop off any coroutine dispatcher)
8. **No Hilt** - single `AppContainer` built in `Application.onCreate()`; the app is 1 screen/1
   service/1 repository, small enough that a DI framework's codegen isn't earning its keep (see
   `contexts/design-patterns.md`)
9. **No comments except non-obvious WHY** - a hidden constraint, a workaround, a subtlety. Never
   restate what the code already says.

---

## Operational Guidelines

### Build & Test Workflow

See `contexts/commands.md` for the full command reference.

**After any code change**:
1. Build must succeed
2. All unit tests must pass
3. If the change touches `vpn/`, re-run the relevant section(s) of `docs/manual-testing.md`
   on-device before considering the task done

---

### Architecture Patterns

See `contexts/architecture.md` and `contexts/design-patterns.md` for full detail. Summary:

- **Single Gradle module** (`:app`) — no multi-module split; the app is small enough that module
  boundaries would be pure overhead (contrast with Raven's `:core`/solver-module split, which
  exists to prevent a real circular dependency Anglerfish doesn't have).
- **MVVM**: Compose UI → `AppListViewModel` (exposes `Flow<AppListUiState>` + one-shot
  `Flow<AppListEvent>`) → `AppRepository` (DataStore) / `VpnGateway` (VpnService).
- **Dependency Injection**: manual, one `AppContainer` built in `AnglerfishApplication.onCreate()`
  — no Hilt.
- **Testability seam**: `VpnGateway` interface exists solely so the ViewModel can be unit-tested
  without a real `Context`/`VpnService`.

---

### Tech Stack

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Language** | Kotlin | 2.0.20 | Modern JVM language with null-safety |
| **Platform** | Android SDK | 26-36 | Android 8.0 to Android 16 |
| **UI** | Jetpack Compose | BOM 2024.09.02 | Declarative UI framework |
| **Design** | Material Design 3 | (via Compose BOM) | Modern Material Design |
| **DI** | None (manual `AppContainer`) | — | App is too small to justify Hilt's codegen |
| **Persistence** | Jetpack DataStore (Preferences) | 1.1.1 | Selected-app set + active flag |
| **Async** | Coroutines | 1.9.0 | Structured concurrency |
| **Build** | Gradle KTS | AGP 8.6.0 | Kotlin DSL build scripts, version catalog |
| **Testing** | JUnit4 + kotlinx-coroutines-test | 4.13.2 / 1.9.0 | Unit testing |

These are floor versions — bump to newer stable patches at implementation time if available (see
the implementation plan's Global Constraints).

Versions are **not** required to match the sibling Android projects Raven (`C:\dev\repos\GitHub\Raven`)
and Otter (`C:\dev\repos\GitHub\otter`) — Anglerfish is much smaller and standalone, and picks its
own floor versions. The `.claude/` documentation *structure* mirrors both for cross-project
consistency; the Hilt/multi-module/Detekt+Kover apparatus those two use is deliberately not
adopted here — see `contexts/design-patterns.md`'s "Patterns Deliberately Not Yet In Use".

---

## Communication Style

### Language

**Code/Documentation/Commits**: English (always)
**Conversation**: can be customized in `.claude/CLAUDE.local.md`

---

### Decision Making

**When proposing solutions**:
1. Present 2-3 alternatives
2. List pros/cons for each
3. State recommendation with reasoning
4. Wait for user choice

**Before major changes**:
1. Analyze current code
2. Propose approach with trade-offs
3. Show impact (files affected, effort estimate)
4. Get approval before coding

---

## Project Structure

### Directory Layout

```
Anglerfish/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/app/anglerfish/
│       │   │   ├── AnglerfishApplication.kt
│       │   │   ├── di/AppContainer.kt
│       │   │   ├── data/                  # domain model, filtering, DataStore repository
│       │   │   ├── vpn/                   # VpnGateway/VpnController/AnglerfishVpnService
│       │   │   └── ui/                    # Compose screen, ViewModel, MainActivity
│       │   └── res/
│       └── test/java/app/anglerfish/      # unit tier only — see contexts/tests.md
├── docs/
│   └── manual-testing.md                  # on-device VpnService verification checklist
└── .claude/
    ├── CLAUDE.md                          # this file
    ├── contexts/                          # living project docs (this @-included set)
    └── sessions/{specs,plans}/            # point-in-time design specs and SDD plans
```

---

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/java/app/anglerfish/vpn/AnglerfishVpnService.kt` | The black-hole tunnel mechanic |
| `app/src/main/java/app/anglerfish/vpn/VpnGateway.kt` | Testability seam between ViewModel and VpnService |
| `app/src/main/java/app/anglerfish/data/DataStoreAppRepository.kt` | Selected-app set + active flag persistence |
| `app/src/main/java/app/anglerfish/ui/AppListViewModel.kt` | Activate/deactivate/toggle state logic |
| `app/src/main/java/app/anglerfish/di/AppContainer.kt` | Manual DI, single registration point |
| `docs/manual-testing.md` | VpnService on-device verification checklist |

---

## Project Documentation Files

**Core Documentation** (`.claude/` directory):
- `.claude/CLAUDE.md` - This file (project instructions)
- `.claude/contexts/kanban.md` - Task tracking, backlog, session history
- `.claude/contexts/architecture.md` - Architecture diagrams, design decisions
- `.claude/contexts/design-patterns.md` - Patterns in use and why
- `.claude/contexts/commands.md` - Full command reference
- `.claude/contexts/conventions.md` - Coding/commit/test conventions
- `.claude/contexts/tests.md` - Test counts, directory structure

**Point-in-time Documentation** (`.claude/sessions/` directory, not living docs):
- `.claude/sessions/specs/*.md` - Design specs from brainstorming
- `.claude/sessions/plans/*.md` - Implementation plans from writing-plans/SDD

**Public Documentation** (committed to git):
- `README.md` - public project overview, build instructions
- `docs/manual-testing.md` - on-device VpnService checklist
- GitHub Wiki - product requirements, architecture, conventions (mirrors this
  `.claude/contexts/` set at a summary level)

---

## References

### Available Skills

- `/update-context` - Update kanban.md/architecture.md/etc. after finishing work
- `/project-setup` - Initialize or update `.claude/` structure
- `/analyze-commit` - Pre-commit analysis (security, quality, tests)
- `/skill-setup` - Create or update skills

---

### Android-Specific Conventions

**Package Structure**:
```
app.anglerfish.data.*   # domain model, filtering, DataStore repository
app.anglerfish.vpn.*    # VpnGateway/VpnController/AnglerfishVpnService
app.anglerfish.ui.*     # Compose UI, ViewModel, MainActivity
app.anglerfish.di.*     # manual DI (AppContainer)
```

**Naming Conventions**:
- Activities: `*Activity.kt`
- ViewModels: `*ViewModel.kt`
- Composables: PascalCase functions
- Sealed event/state interfaces: `*Event.kt` / `*UiState.kt`

**Test Structure**: see `contexts/conventions.md` and `contexts/tests.md`.
