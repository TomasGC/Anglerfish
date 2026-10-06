# Commands - Anglerfish Build & Test

Build and test commands for the Anglerfish Android project.

---

## scripts/manage.py (Python dev-tooling CLI)

Ported from the sibling Raven project (issue #19). Dispatches to action classes under
`scripts/src/cli/actions/`; `common/subprocess_runner.SubprocessRunner` is the injection seam
every action is tested against.

```bash
# Build debug APK, bump versionCode/versionName, install on a connected/auto-discovered device
python scripts/manage.py build
python scripts/manage.py build --no-install

# Run the Kotlin JVM suites: :core (test, integrationMock, integrationReal) and :app unit tests.
python scripts/manage.py test
python scripts/manage.py test unit
python scripts/manage.py test instrumented

# Validate branch name, commit messages, no-TODO/FIXME (app/src only — single module), large files
python scripts/manage.py validate

# Generate the Kover XML report and print a line-coverage percentage (soft 80% warning only —
# nothing in the Gradle build or CI fails over coverage yet, see contexts/design-patterns.md)
python scripts/manage.py coverage

# Wireless ADB pairing/connect via mDNS discovery
python scripts/manage.py adb connect
python scripts/manage.py adb connect --pair 123456 --pair-address 192.168.1.10:45678
```

### scripts/ tests

```bash
cd scripts
python -m pytest tests/ -v                       # full suite (unit + integration_real + e2e)
python -m pytest tests/ -m "not integration_real" # CI-safe tier — no local Android SDK required
```

`tests/integration_real/` exercises the real, locally installed `adb`/emulator (no mocks);
`tests/e2e/` is currently empty (no scripts genuinely need subprocess-level e2e coverage yet),
same as Raven's own copy.

---

## Code Quality & Coverage

```bash
./gradlew detekt              # static analysis — config/detekt/detekt.yml, see design-patterns.md
                               # for the Composable-aware overrides
./gradlew lintDebug           # Android Lint
./gradlew koverXmlReportDebug # app/build/reports/kover/reportDebug.xml
```

---

## CI (GitHub Actions, via `TomasGC/condor`'s reusable workflows)

- `push-ci.yml` — runs on push to `feature/**`/`bugfix/**`. Kotlin: `validation` and `lint-checks` run in
  parallel with `unit` (matrix: `:core:test`, `:app:testDebugUnitTest`). Then `integration-mock`
  (`:core:integrationMock`), `integration-real` (`:core:integrationReal`), `build-apk` and `coverage`, and
  `instrumented-tests` on a Gradle Managed Device. Each tier waits for the one before it. Python:
  `python-pipeline` (skipped when nothing under `scripts/` changed).
- `pr-ci.yml` — reports Push-CI's result back onto the PR once it completes.
- `.osv-scanner.toml` — overrides for AGP/Gradle-internal build-tool transitive dependencies
  (netty, bouncycastle, commons-*, jose4j, freemarker, jdom2, the Kotlin Gradle plugin itself) —
  never shipped in the APK, so their CVEs don't apply to this app. Copied from Raven, which hits
  the identical set (same AGP/Gradle version family).
- `gradle/verification-metadata.xml` — regenerate with
  `./gradlew --write-verification-metadata sha256 --refresh-dependencies --no-daemon clean assembleDebug testDebugUnitTest detekt lintDebug koverXmlReportDebug`
  if CI starts failing with "Dependency verification failed" for a new artifact. Run once per
  platform if the failure is for a platform-specific artifact (e.g. `aapt2`) — see
  `contexts/design-patterns.md`'s "Cross-Platform Gradle Dependency-Verification Regeneration".

---

## Manual Verification (VpnService)

`VpnService.Builder.establish()` cannot be exercised via Gradle — run the checklist in
`docs/manual-testing.md` on a real device or emulator (API 26+) after any change touching
`vpn/` or the activation flow in `AppListViewModel`/`MainActivity`.

## Project Setup (first run)

```bash
git clone https://github.com/TomasGC/Anglerfish.git
cd Anglerfish
python scripts/manage.py build --no-install
```
