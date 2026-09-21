# Commands - Anglerfish Build & Test

Build and test commands for the Anglerfish Android project.

---

## Android Build & Test

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests (JVM, fast, no device)
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "app.anglerfish.data.AppListFilterTest"

# Build + run all unit tests
./gradlew build
```

## Manual Verification (VpnService)

`VpnService.Builder.establish()` cannot be exercised via Gradle — run the checklist in
`docs/manual-testing.md` on a real device or emulator (API 26+) after any change touching
`vpn/` or the activation flow in `AppListViewModel`/`MainActivity`.

## Project Setup (first run)

```bash
git clone https://github.com/TomasGC/Anglerfish.git
cd Anglerfish
./gradlew assembleDebug
```
