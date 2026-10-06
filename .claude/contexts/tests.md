# Tests - Anglerfish

**Last Updated**: 2026-10-05

---

## Counts (JUnit XML of the last full run)

| Tier | Source set | Classes (tests) | Tests |
|------|-----------|-----------------|-------|
| Unit (pure JVM) | `core/src/test` | AppListFilterTest 4, BlocklistParsingTest 15, DnsInterceptorTest 10, DnsMessagesTest 4, Ipv4UdpPacketTest 9, FlowKeyTest 3, Ipv4TcpPacketTest 20, NatRelayTest 2, SessionTableTest 7, TcpStateMachineTest 26 | 100 |
| Integration-mock | `core/src/integrationMock` | DnsInterceptorPipelineTest 3 | 3 |
| Integration-real | `core/src/integrationReal` | DataStoreAppRepositoryTest 16, DataStoreBlocklistRepositoryTest 11 | 27 |
| Android unit | `app/src/test` | AppListViewModelTest 23, BlocklistViewModelTest 5, Fake/DataStore contract tests 3 + 3 | 34 |
| Instrumented | `app-instrumented/src/main` | ActivationFlowTest | 1 |
| Manual | `docs/manual-testing.md` | 8 on-device scenarios (VpnService, notification, process death) | — |
| **Total automated** | | | **165** |

Per-test descriptions were in the old counts table; the test sources are the reference now.

**`NatRelay`/`UdpRelay`/`TcpRelay` are not wired into the live tunnel**: issue #41 built the NAT/TCP-relay engine as a standalone, fully-tested-at-the-logic-layer component (`app.anglerfish.nat`), same scope boundary as #38/#39/#40 — `AnglerfishVpnService.drain()` still discards every packet, unchanged. Wiring it in, with per-app `BlockMode` dispatch choosing between the DNS-only ad-filter path and this full relay, is #42's job.

**Known deferred item (flagged for issue #42)**: `TcpRelay.handle()` writes to the real destination socket synchronously on whatever thread calls it. Harmless with no live caller today, but #42's future single tun-drain thread calling `handle()` directly would have one slow upload stall every other flow sharing that thread. The right fix (a bounded per-relay write queue, ideally sized to double as the advertised receive window) depends on #42's actual drain-loop threading model, which doesn't exist yet — same reasoning as the `isBlocked()` caching and `DnsResolverProvider` deferrals already carried into #42 from #39/#40.

**`DnsInterceptor` is not wired into the live tunnel**: issue #40 built it as a standalone,
fully-tested component (`app.anglerfish.dns`) — `AnglerfishVpnService.drain()` still discards every
packet, unchanged. Wiring it in, with per-app `BlockMode` gating, is #42's job. See
`contexts/design-patterns.md`'s #40 entries for why.

**No bundled blocklist asset**: the original design bundled a snapshot of the full StevenBlack/hosts
list as an app asset so first-launch-before-any-fetch still blocked something. That 2.2MB asset
tripped the shared CI large-file gate (500KB cap, even gzip-compressed) — dropped entirely rather
than trimmed, since `AnglerfishApplication.onCreate()` already calls `refreshIfStale()`
unconditionally and `lastFetch` defaults to 0, so first launch always starts a real fetch
immediately. See `contexts/design-patterns.md`'s "No Bundled Snapshot" entry.

**Known deferred performance item (flagged for issue #39, re-deferred to #42)**:
`DataStoreBlocklistRepository.isBlocked` re-parses the cached remote text and rebuilds the full
remote+additions union on every call — still harmless today, since issue #40's `DnsInterceptor`
calls `BlocklistRepository.isBlocked` (an interface, injected via a fake in its own tests) but
isn't wired into any live caller yet, same deliberate scope boundary as #38 and #39. #42, which
wires `DnsInterceptor` into the live `AnglerfishVpnService` tunnel, is now the issue that actually
turns this into a per-DNS-query cost. The right caching strategy (a `StateFlow`-backed merged set,
invalidated on DataStore change) stays deferred to #42, to design alongside its real call pattern
(per-query vs. batched) rather than guessed at speculatively here.

`InstalledAppsProvider` (the `PackageManager` glue) and the Compose UI (`AppListScreen`,
`MainActivity`) are not unit tested — they have no branching logic of their own once the pure
filter and the ViewModel are covered; correctness is verified by running the app (see
`docs/manual-testing.md`).

**Why `vpn/` stays manual**: `VpnService.Builder.establish()` requires a real (or emulated)
Android VPN subsystem — Robolectric doesn't shadow it meaningfully, and there's no fake to
substitute without testing a fake instead of the real mechanic. Same trade-off Raven documents
for `OverlayService`'s `WindowManager` calls.

**Why no `ViewModel` + real-repository integration test**: tried during issue #38 and reverted.
`viewModelScope` is fire-and-forget with no public way to await or cancel it from test code, which
made a bare JVM test (no Robolectric, no device) genuinely flaky — `Dispatchers.setMain()` is a
trick built for fake/synchronous dependencies, and pairing it with a real async one surfaced a real
reentrancy hang (`Dispatchers.Unconfined` resuming inline on DataStore's own actor thread) and a
cross-test leak (an orphaned coroutine throwing into an unrelated test once `TemporaryFolder`
deleted its backing file). Issue #27's `integration-mock` tier (Robolectric, not yet built) is the
right place for ViewModel-plus-real-dependency testing — Robolectric's `ShadowLooper.idle()` gives
a real drain signal a bare JVM test doesn't have. The contract test above gets the actual value this
was chasing (Fake/real parity) without needing a ViewModel in the loop at all.

---

## Directory Structure

One source set per tier (see the table above). Tests stay in the source set of their tier; there is no
name-based filter.

```
core/src/test/java/app/anglerfish/{data,dns,nat}/
core/src/integrationMock/java/app/anglerfish/dns/DnsInterceptorPipelineTest.kt
core/src/integrationReal/java/app/anglerfish/data/
app/src/test/java/app/anglerfish/{data,ui}/      # AppRepositoryContractTest (abstract) + Fake/DataStore subclasses
app-instrumented/src/main/java/app/anglerfish/e2e/ActivationFlowTest.kt
```

The Compose UI, `vpn/` and the PackageManager glue stay on the manual checklist (`docs/manual-testing.md`).
Robolectric is not used.

---

## Run Commands

```bash
./gradlew testDebugUnitTest                                       # Android unit (:app)
./gradlew :core:test :core:integrationMock :core:integrationReal  # pure JVM tiers
./gradlew :app-instrumented:connectedDebugAndroidTest             # on-device (emulator or device)
python scripts/manage.py test       # all JVM tiers: :core test/integrationMock/integrationReal + :app unit
python scripts/manage.py coverage   # same, plus a Kover line-coverage percentage
```
