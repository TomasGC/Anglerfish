# Conventions - Anglerfish

Coding and commit conventions for the Anglerfish Android project.

---

## Commit Format

**Format**: `#XXX: type: description`

**`docs` exception**: `docs: description` — documentation commits never carry an issue number,
anywhere in the message (not as a prefix, not in prose either), no exceptions — including a doc
that happens to close out a specific issue, like a manual test checklist.

**Types**: feat, fix, refactor, test, docs, chore

**Examples**:
```
#1: feat: add app-list domain model and system/self filtering
#6: feat: implement per-app VpnService black-hole tunnel
docs: add manual VpnService verification checklist
docs: update contexts for ViewModel state logic
```

**Rules**:
- Always prefix with the GitHub issue number, except `docs` commits which never have one
- Description: WHAT/WHY, not HOW/WHO
- No stats (+XX lines), no implementation details, no emoji, no AI/assistant references

---

## Branch Naming

- Features: `feature/XXX-description`
- Bugfixes: `bugfix/XXX-description`

---

## Kotlin/Android Conventions

### Package Structure

```
app.anglerfish.data.*   # domain model, filtering, DataStore repository
app.anglerfish.vpn.*    # VpnGateway/VpnController/AnglerfishVpnService
app.anglerfish.ui.*     # Compose UI, ViewModel, MainActivity
app.anglerfish.di.*     # manual DI (AppContainer)
```

### Naming

- Activities: `*Activity.kt`
- ViewModels: `*ViewModel.kt`
- Composables: PascalCase functions
- Sealed event/state interfaces: `*Event.kt` / `*UiState.kt`

### Test Structure

```
app/src/test/java/app/anglerfish/
├── data/     # pure logic, JVM-only DataStore tests, and the Fake/real contract test
└── ui/       # ViewModel tests against fakes, plus the shared FakeAppRepository fixture
```

No Raven/Otter-style tier-suffix directory convention (`tests/unit/`, `tests/integration/mock/`)
— Anglerfish's two automated categories are unit tests (`*Test.kt`) and one contract test
(`AppRepositoryContractTest`, run via `FakeAppRepositoryContractTest`/
`DataStoreAppRepositoryContractTest`) verifying `FakeAppRepository` matches
`DataStoreAppRepository`'s real behavior. A `ViewModel`-plus-real-repository integration tier was
tried during issue #38 and reverted (real, documented flakiness, not a style choice) — see
`contexts/tests.md` for the full reasoning and why `vpn/`/the Compose UI still stay manual.

### Code Quality

See `.claude/CLAUDE.md`'s Code Quality Standards section — same list applies here (no hardcoded
values, one class per file, strong typing, DRY, immutability, null safety, structured
concurrency, no Hilt, no comments except non-obvious WHY).
