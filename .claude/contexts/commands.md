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
./gradlew koverXmlReportMerged # app/build/reports/kover/reportMerged.xml (:app and :core)
```

---

## CI (GitHub Actions, via `TomasGC/Condor`'s reusable workflows, called at `@v1`)

- `push-ci.yml` — runs on push to `feature/**`/`bugfix/**`. Kotlin: `validation` and `lint-checks` run in
  parallel with `unit` (matrix: `:core:test`, `:app:testDebugUnitTest`). Then `integration-mock`
  (`:core:integrationMock`), `integration-real` (`:core:integrationReal`), `build-apk` and `coverage`, and
  `e2e-android` (`app-environments: ["android"]`: the instrumented tests on an emulator, two shards, one
  `E2E (android)` summary). Each tier waits for the one before it. Python:
  `python-pipeline` (skipped when nothing under `scripts/` changed).
- `pr-ci.yml` — runs on the pull request itself (opened, reopened, synchronize, edited): title format, secret
  scan and the context check. `code-paths` and `test-paths` cover `:app`, `:core` and `:app-instrumented`.
- `.osv-scanner.toml` — eight entries, each scoped to the exact version and naming its source: build tooling (the
  Gradle build classpath, and the Android lint tool for some) that the scanner flags in the metadata and that is
  never on the APK's classpath. To see what the scanner flags, run it with no config; drop an entry when its package
  leaves the metadata or stops being flagged (the header of the file says so). Re-check 2026-12-05.
- `gradle/verification-metadata.xml` — after a dependency change, empty its `<components>` (the scan reads this
  file, so stale versions would keep being flagged), rebuild the Windows entries with `./gradlew
  --write-verification-metadata sha256 --refresh-dependencies clean assembleDebug :app:testDebugUnitTest :core:test
  :core:integrationMock :core:integrationReal detekt lintDebug koverXmlReportMerged koverHtmlReportMerged
  pixel4api30Setup`, then add the Linux entries (`aapt2` has separate jars) with a throwaway push-triggered Actions
  job (KVM enabled, same command, uploads the file) on a branch that is not `feature/**`. See
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
