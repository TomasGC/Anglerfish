# Conventions - Anglerfish

Coding and commit conventions for the Anglerfish Android project.

---

## Commit Format

**Format**: `#XXX: type: description`

**`docs` exception**: `docs: description` — documentation commits never carry an issue number,
no exceptions (including a doc that happens to close out a specific issue, like a manual test
checklist).

**Types**: feat, fix, refactor, test, docs, chore

**Examples**:
```
#1: feat: add app-list domain model and system/self filtering
#6: feat: implement per-app VpnService black-hole tunnel
docs: add manual VpnService verification checklist
docs: update contexts for issue #6
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
├── data/     # pure logic + JVM-only DataStore tests
└── ui/       # ViewModel tests against fakes (FakeAppRepository, FakeVpnGateway)
```

No tier-suffix naming convention (unlike Raven's `*IntegrationTest`/`*RealIntegrationTest`) —
Anglerfish has only one automated tier (unit). See `contexts/tests.md` for why `vpn/` and the
Compose UI stay manual instead of gaining their own automated tiers.

### Code Quality

See `.claude/CLAUDE.md`'s Code Quality Standards section — same list applies here (no hardcoded
values, one class per file, strong typing, DRY, immutability, null safety, structured
concurrency, no Hilt, no comments except non-obvious WHY).
