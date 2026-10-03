# Design Patterns - Anglerfish

**Last Updated**: 2026-10-02

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

## Patterns In Use (added in issue #40)

### A Pure-JVM Library for a Bug-Prone Wire Format

`DnsMessages` wraps `dnsjava` (new dependency, BSD-2-Clause, pure Java, no native code) rather
than hand-rolling DNS message parsing/building. Domain-name decompression in particular is a
well-known source of real bugs — compression-pointer loops are a classic CVE class — unlike
`Ipv4UdpPacket`'s hand-rolled envelope, which has fixed-size headers and no variable-length
encoding and so carries none of that risk. Being pure JVM (no Android framework dependency) is a
second win beyond correctness: `DnsMessagesTest` runs as plain JUnit, no Robolectric needed, same
tier as `BlocklistParsingTest`.

### Revert an Ill-Conceived Live-Code Addition Entirely, Don't Patch Around It

A `Builder.setUnderlyingNetworks()` call was added to `AnglerfishVpnService.buildTunnel()` during
this issue's own Task 3, intended to give a future `DnsResolverProvider` a network to query. The
final review found it didn't work: `VpnService` exposes no way to read "the network I set as
underlying" back out, so it gave a future caller nothing usable, while its side effects were
actively harmful — pinning one `Network` stops the system's default tracking, so after a Wi-Fi to
cellular handoff the VPN reports a disconnected network's capabilities until the next restart —
and it required a new manifest permission for that zero benefit. The fix was deletion, not a
smaller patch: removing the block reverted `buildTunnel()` to its already-verified #5 shape
byte-for-byte, so no new on-device manual verification was needed at all. A half-fix (adding the
permission, keeping the broken call) would have "resolved" the crash while leaving the handoff
regression and the non-functional network-tracking mechanism in place.

### `connect()` a Forwarding Socket Before `receive()`

`UdpDnsForwarder`'s `DatagramSocket` calls `socket.connect(resolver, DNS_PORT)` before `send()`.
An unconnected `DatagramSocket` accepts a reply from *any* source to that ephemeral port — the
16-bit DNS transaction ID would be the only thing standing between a real resolver's answer and a
spoofed one arriving first. `connect()` makes the kernel drop datagrams from any other address,
closing that gap for the cost of one line — found by the final review, not written defensively up
front, since the original design reasoned about `protect()` (needed to escape the tunnel) without
separately reasoning about who's allowed to answer once escaped.

### Exception Boundary at the Orchestrator, Not Every Leaf

`DnsInterceptor.resolveResponse` wraps its whole body in one try/catch (rethrowing
`CancellationException`, swallowing everything else to `null`), rather than each dependency call
guarding itself. `Ipv4UdpPacket.parse` and `DnsMessages.parseQuery` already return `null` for
malformed *input* — that's their own job. But `BlocklistRepository.isBlocked` (DataStore I/O) and
`DnsResolverProvider.currentResolvers` (`ConnectivityManager`, which can throw `SecurityException`
on a missing permission) are real dependencies with real failure modes that have nothing to do
with the packet being parsed — the spec's "never crash, always an ordinary failed lookup"
requirement applies to those too, and one boundary at the point where `handle()`'s contract is
actually promised is simpler and harder to miss than a try/catch inside each dependency's own
real implementation.

## Patterns In Use (added in issue #41)

### Lock Around State Computation, I/O Outside the Lock

`TcpRelay.transition { compute -> ... }` is the single path every call site uses to read, compute,
and write `connection`: `synchronized(stateLock) { connection = compute(connection) }` happens
first, and only the resulting actions (tun writes, the blocking socket write/shutdown) run outside
the lock. The relay's own IO coroutine and the packet-handling caller both drive state from
different threads; `@Volatile` alone made each individual read and write visible across threads
but not the read-compute-write sequence atomic, so whichever thread finished last silently
discarded the other's sequence/ack advance. Keeping I/O outside the lock means a slow blocking
write never stalls the other thread's state transitions, only its own.

### Object Split Purely to Stay Under a Detekt Threshold

`TcpStateMachine` (public entry points) and `TcpTransitions` (file-private implementation) live in
the same file with no real API boundary between them — the split exists solely because the
combined logic needed 14-15 functions and this project's detekt config caps `TooManyFunctions` at
11. Splitting by "one function per real-world event" vs. "per-state transition detail" keeps the
file readable as a size split, not an architectural one; a future rule change could re-merge them
without consequence.

### Conditional Removal Guards an `onClosed` Race

`SessionTable<T>.remove(key, expected: T): Boolean` only deletes an entry if the stored value is
still the exact instance (`!==`) passed in. Both `UdpRelay` and `TcpRelay` fire their `onClosed`
callback through this overload instead of a bare `remove(key)`, so a relay that dies after
`NatRelay` has already installed its replacement under the same key (a fresh SYN for a 5-tuple
whose old connection just failed) can't delete the wrong, newer entry. The invariant is enforced at
the one call site that can check it, not left as a rule every caller has to remember.

### Window-Paced Backpressure Without Retransmission

`TcpRelay.relayFromDestination` throttles how fast it reads from the real destination socket to
what the app's advertised TCP receive window actually allows (`TcpConnection.appAckNumber`/
`appWindow`, refreshed from every ACKed segment), polling and retrying rather than reading ahead.
This is deliberately *pacing*, not *reliability*: the relay still has no retransmission of its own
(per the original spec's "no retransmission timers" decision), so it still cannot recover data the
app's kernel actually drops — it only avoids being the thing that force-feeds data faster than the
app said it could take, which was turning every transfer larger than one window into a permanent
stall rather than an occasional loss.

### Idempotent `close()` via `AtomicBoolean`, Not a Nullability Check

Both `UdpRelay.close()` and `TcpRelay.close()` guard their body with
`if (!closed.compareAndSet(false, true)) return` rather than checking whether a socket/job field is
already null. A failed `send()`/`write()` on the caller's thread and the relay's own IO coroutine's
failure path can both reach `close()` for the same relay at the same time; `compareAndSet` makes
exactly one of them win the single call to `onClosed()`, so a losing racer's `SessionTable.remove`
can never fire for a key a replacement relay may already occupy — this is what the conditional
`remove(key, expected)` above also protects against from the other direction.

## Patterns In Use (added in issue #49)

### Factory Seam at the Real-Socket Boundary

`NatRelay` never constructs `UdpRelay`/`TcpRelay` itself. It asks a `SessionFactory`, and the
production `RelaySessionFactory` holds the `VpnService`, `TunWriter` and `CoroutineScope` that real
relays need. Tests pass a fake factory whose sessions only record `close()` and `start()`. The
seam goes at the session interface rather than at the timeouts: a test that only asserts the
timeout constants passes even when the two `evictIdle` calls are swapped. Keeping `VpnService` out
of `NatRelay`'s constructor also means the test needs no Android types.
