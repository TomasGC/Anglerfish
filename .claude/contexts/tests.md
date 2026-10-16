# Tests - Anglerfish

**Last Updated**: 2026-10-05

---

## Counts (JUnit XML of the last full run)

| Tier | Source set | Classes (tests) | Tests |
|------|-----------|-----------------|-------|
| Unit (pure JVM) | `core/src/test` | AppListFilterTest 4, BlocklistParsingTest 15, DnsInterceptorTest 10, DnsMessagesTest 4, Ipv4UdpPacketTest 9, FlowKeyTest 3, Ipv4TcpPacketTest 20, NatRelayTest 3, SessionTableTest 8, TcpStateMachineTest 26, BlockModeResolutionTest 5, ProcNetLineTest 16, RetryingLookupTest 3 | 126 |
| Integration-mock | `core/src/integrationMock` | DnsInterceptorPipelineTest 3 | 3 |
| Integration-real | `core/src/integrationReal` | DataStoreAppRepositoryTest 16, DataStoreBlocklistRepositoryTest 12 | 28 |
| Android unit | `app/src/test` | AppListViewModelTest 23, BlocklistViewModelTest 5, Fake/DataStore contract tests 3 + 3, FlowBlockModeResolverTest 6 | 40 |
| Instrumented | `app-instrumented/src/main` | ActivationFlowTest | 1 |
| Manual | `docs/manual-testing.md` | 8 on-device scenarios (VpnService, notification, process death) | — |
| **Total automated** | | | **198** |

Per-test descriptions were in the old counts table; the test sources are the reference now.

**#42 wired both `NatRelay` and `DnsInterceptor` into the live tunnel**: `AnglerfishVpnService.dispatch()`
now sends each flow to one or the other, gated by `FlowBlockModeResolver`'s resolved `BlockMode`
(`FullBlock` still discards, byte-for-byte as before). `UidResolver`/`ConnectivityManagerUidResolver`/
`ProcNetUidResolver`/`SdkGatedUidResolver`/`FileDescriptorTunWriter` stay untested real glue, same
treatment as `InstalledAppsProvider`/`AnglerfishVpnService` itself — only `FlowBlockModeResolver`'s own
orchestration (cache, the three uid/package failure modes) is unit-testable, via injected fakes.

**An automated on-device dispatch test was attempted and removed (#42 final review)**: the goal was
an instrumented test proving UID attribution → `FlowBlockModeResolver` → `DnsInterceptor` end to
end, deterministically. Self-tunneling (selecting `app.anglerfish`'s own package so the test
process's own traffic enters its own tunnel) turned out to be unreliable across devices: on a real
phone, `AnglerfishVpnService`'s own process never actually routed through its own VPN even once
added to the allow-list (consistent with `filterUserLaunchableApps` already excluding Anglerfish
from the selectable app list in production — a real device apparently never lets a VPN-owning app's
own traffic enter its own tunnel). A second attempt called `UidResolver` directly against a real
socket while some other package was tunneled (no self-selection) and found
`ConnectivityManager.getConnectionOwnerUid()` requires the queried flow to actually be inside the
caller's own VPN, not merely that the caller is the active VPN app — so a non-tunneled socket's own
uid was never resolvable either, with no amount of retrying. Proving this specific mechanism
end-to-end on real hardware without a second, separate test app generating real tunneled traffic
is not reliably achievable with reasonable effort; `ActivationFlowTest` remains the automated
on-device coverage, hardened with `ensureDeactivated()`/`setMode` (see below) so it no longer
depends on execution order. The mechanism itself was verified correct through extensive real manual
testing during this issue (Chrome traffic, real blocklist domains correctly NXDOMAIN'd) — see the
#42 kanban entry.

Writing (and debugging) that attempt surfaced three real, independent findings, all fixed: (1)
`drain()`'s blocking read had no exception handling, and `teardownTunnel()`'s bounded join could
let `descriptor?.close()` run while a read was still in flight, throwing an uncaught `IOException`
that killed the process — now caught, same as the existing EOF case. (2) Every test that clicks the
switch needs a known starting state, since instrumentation runs every test class in one process; a
prior test's never-deactivated tunnel left the persisted `isActive` flag true, and the switch's
checked state (bound to that flag) made the next test's own click deactivate instead of activate —
`ensureDeactivated()` (`InstrumentedTestSupport.kt`) now drives to "off" through the real switch
first, and `ActivationFlowTest` uses `setMode` (an upsert) instead of `toggleSelection` so it stays
correct regardless of a prior test's selection state. (3) `ProcNetUidResolver` only scanned
`/proc/net/{tcp,udp}`, missing a dual-stack socket's real conntrack entry in `tcp6`/`udp6`
(IPv4-mapped-address form) — fixed, see below.

**Deferred item from #41 still open, now confirmed live (#42 final review, I1)**: `TcpRelay.handle()`'s
synchronous write to the real destination socket runs on the one tun-drain thread every selected
app's packets share — the risk the original deferral named ("a future shared drain thread would call
it from") is exactly what #42 built. DNS's own blocking call was moved off that thread (`dispatch()`
now `serviceScope.launch`es `DnsInterceptor.handle()` instead of `runBlocking` it — a slow/unresponsive
resolver used to stall every app's traffic, not just that query's, for up to `UdpDnsForwarder`'s
`FORWARD_TIMEOUT_MS`), and `DataStoreBlocklistRepository.isBlocked` now caches its parsed remote-list
`Set<String>` instead of re-parsing the full multi-hundred-thousand-line hosts text on every single
query. `TcpRelay`'s own write queue is still the open piece — the fix (a bounded per-relay queue)
needs its own scoped pass, not bundled into this one.

**`FlowBlockModeResolver` no longer caches a failed uid lookup for the full 10-minute window
(#42 final review, I4)**: `ConnectivityManager.getConnectionOwnerUid()`'s conntrack race (the reason
`ConnectivityManagerUidResolver` retries, see `RetryingLookup`) is usually transient — caching it as
`FullBlock` used to mean a retransmitted SYN on the same flow moments later hit the stale cache
instead of a fresh, likely-successful attempt. A resolved uid with no selected owning package is
still a stable fact and is cached normally. The same change added an exception boundary around the
whole lookup (`SecurityException` once this app is no longer the active VpnService, `IOException`
from the `/proc/net` fallback) — same class of bug as the `drain()` crash above, same fix shape.

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
