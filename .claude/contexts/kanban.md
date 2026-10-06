# Kanban - Anglerfish

Track of work sessions and completed tasks linked to GitHub issues.

---

2026-10-05 - [#53] Split test tiers into Gradle source sets
- `:core` JVM module holds the pure logic with `test`, `integrationMock` and `integrationReal` source sets; `:app` keeps the Android unit tests; `:app-instrumented` (`com.android.test`) runs the on-device `ActivationFlowTest`
- Discovery-based Condor pipeline (Condor #2, #4, #5) failed at startup with zero jobs and no log: reverted in Condor #6; Anglerfish pins Condor `main` with `unit-job: true`
- Instrumented test seeds the app selection through `AppContainer`: the CI emulator has no user-installed apps, so the app list had no checkboxes
- Not done: CI runs `:app` unit tests only; the `:core` tiers are not in the pipeline yet. `manage.py test` runs all JVM tiers
tags: #ci-cd #test-tiers #modules
Ref: https://github.com/TomasGC/Anglerfish/issues/53
Commits: 5bc3a0b

---

2026-10-03 - [#49] Verify NatRelay's per-protocol session timeouts
- `NatRelay` takes a `SessionFactory` instead of building `UdpRelay`/`TcpRelay` directly. Production wiring is `RelaySessionFactory` (holds `VpnService`, `TunWriter`, `CoroutineScope`); `UdpRelay`/`TcpRelay` implement the new `UdpSession`/`TcpSession` interfaces, with no behavior change
- `NatRelayTest` drives `handleOutgoingPacket` with a UDP datagram and a TCP SYN, then calls `evictIdle`: the UDP session closes at 60s and not at 59.999s, the TCP session outlives the UDP timeout and closes at 5 min and not at 5 min minus 1 ms. Fake sessions are nested in the test class
- Option A (assert the timeout constants) rejected: it passes even if the two `evictIdle` calls are swapped
- Tests: `NatRelayTest` 2 new; NAT suite and full unit suite, `detekt`, `lintDebug` pass
- Push-CI on the branch passed, but several jobs are Raven-shaped (skipped shards, mock/instrumented tiers that run nothing). Tracked in #53, to rework with Condor later
tags: #nat #tcp #testing #seam
Ref: https://github.com/TomasGC/Anglerfish/issues/49
Commit: fb10776

2026-10-03 - [#48] Advertise MSS in the synthesized SYN-ACK
- `TcpStateMachine.onSyn` now advertises MSS 1400 (kind 2, len 4) in the SYN-ACK. The app was falling back to the RFC 1122 default of 536 bytes; both sides now use the same segment size
- `TCP_MAX_SEGMENT_SIZE` (top-level, `TcpStateMachine.kt`) replaces `TcpRelay`'s companion constant, so the advertised MSS and the destination-read buffer can't drift
- `Ipv4TcpSegment` and `TcpSegmentToSend` gain `mss: Int?` (outbound only); `build` sets data offset to 6 words when present
- Tests: 4 new `Ipv4TcpPacketTest` (option bytes, round trip with payload, checksum over the options header, mss in equality), 1 assertion in `TcpStateMachineTest`; `Ipv4TcpPacketTest` 20, `TcpStateMachineTest` 26
- Detekt `TooManyFunctions` (11 max on objects) forced inlining the option write rather than a helper; `Ipv4TcpPacket` stays at 10 functions
tags: #nat #tcp #mss
Ref: https://github.com/TomasGC/Anglerfish/issues/48
Commit: 4bb9161

2026-10-03 - [#50] Fix 5 deferred Minor findings from #41's final review
- `Ipv4TcpPacket.parse` now rejects IP fragments (more-fragments flag or nonzero fragment offset),
  an IHL claiming a header shorter than the 20-byte minimum, and a total-length field claiming more
  bytes than the buffer actually holds — and bounds the parsed payload to that total-length field
  instead of trusting the raw array's own size, so trailing bytes past a packet's real end are no
  longer silently included. Mirrors the equivalent guard `Ipv4UdpPacket` (#40) already had via its
  UDP length field (M1)
- `Ipv4TcpSegment`, `TcpSegmentToSend`, and `TcpAction.DeliverToDestination` now override
  `equals`/`hashCode` to compare their `ByteArray` payload by content — the same reference-equality
  trap that caused one real test failure during #41 itself, now closed at the type level instead of
  left for the next caller to rediscover. `Ipv4TcpSegment`'s override groups its non-array fields
  into one comparable list to keep its own cyclomatic complexity under detekt's threshold (M4)
- Two comments reworded from session-history phrasing ("a prior issue's review flagged...", "as an
  earlier version of this file did") to state the durable WHY directly (M5)
- `UdpRelay.relayLoopBody` dropped a redundant `withContext(Dispatchers.IO)` around `socket.receive()`
  — the whole coroutine already runs on `Dispatchers.IO` (M6a)
- `Ipv4TcpPacketTest` gained a checksum test against a value computed independently in Python (a
  separate RFC 1071 implementation), closing the gap where the two existing checksum tests
  recomputed the same pseudo-header algorithm the implementation itself uses and so couldn't catch
  a shared misunderstanding of it. First attempt's expected value was itself wrong (forgot the
  `segment()` test helper sets `psh=true` for any non-empty payload) — caught immediately since the
  test failed against the real implementation, re-derived correctly (M8)
- M2 (MSS option) and M7 (NatRelay timeout test) spun into their own issues (#48, #49) rather than
  bundled here — each needs its own scoped TDD pass or a convention decision first. M3 (multiple
  types per file, matching #40's precedent) and M6b (buffer pooling, premature optimization) left
  as-is, no issue filed
- 11 new tests (155 total, up from 144): 8 `Ipv4TcpPacketTest`, 3 `TcpStateMachineTest`
tags: #nat #tcp #review #cleanup
Ref: https://github.com/TomasGC/Anglerfish/issues/50
Commits: bd0b8b8, 16b4691, 0dfb08d

---

2026-10-02 - [#41] TCP/UDP NAT relay engine
- Standalone, fully-testable-at-the-logic-layer NAT/relay engine (`app.anglerfish.nat`): given a
  raw IPv4 packet, dispatches it by 5-tuple to a per-flow `UdpRelay` or `TcpRelay` over a real
  protected socket, translating addresses/ports back and forth. Nothing wired into the live
  `AnglerfishVpnService` tunnel — same explicit scope boundary as #38/#39/#40, since per-app
  `BlockMode` dispatch between the DNS-only ad-filter path and this full relay is #42's job.
  Built UDP first (simpler, proves the shared `SessionTable`/NAT-translation infrastructure) before
  TCP's handshake/sequence-tracking state machine, per the approved design
- `SessionTable<T>` (generic, injected-clock `FlowKey -> T` store), `Ipv4TcpPacket` (pure,
  hand-rolled parse/build with mandatory checksum, mirrors #40's `Ipv4UdpPacket`), a pure
  `TcpStateMachine`/`TcpTransitions` object pair (split across two objects purely to stay under
  detekt's function-count threshold) driving a thin, untested `TcpRelay` glue class that owns a
  real `Socket`; `UdpRelay`/`NatRelay` complete the UDP side and the dispatcher
- Fresh-reviewer final review (Opus) on the full branch found 4 Critical and 9 Important findings,
  all fixed in one pass: evicted sessions were never actually closed (`SessionTable.evictIdle` now
  returns the evicted *values*, not just keys, so a caller can close what it removed); the
  synthesized SYN-ACK was sent before the real socket connected, silently dropping the app's first
  real bytes on nearly every connection (moved to after a successful `connect()`, with a proper
  RST-ACK reply on connect failure); `TcpConnection` was read-modified-written from two threads
  with only `@Volatile`, silently losing sequence/ack updates under bidirectional traffic (fixed
  with a lock around state computation, I/O performed outside it); data from the destination was
  pushed into the tunnel with no regard for the app's advertised receive window, permanently
  stalling any transfer larger than that window (fixed with window-paced backpressure, tracked via
  new `appAckNumber`/`appWindow` fields refreshed from every ACKed segment). Also fixed: 32-bit
  sequence-number wraparound (never masked), a FIN bypassing the duplicate/out-of-order check and
  corrupting state, an incomplete teardown model (replaced with proper `CLOSE_WAIT`/`LAST_ACK` half
  close, the FIN now correctly consuming a sequence number), a stale/dead session table entry
  silently absorbing a fresh SYN, `close()` racing an in-progress connect and a session's `onClosed`
  callback deleting the wrong (replacement) entry, failure paths not sending a RST and a real read
  error being mistaken for a clean close, and unhandled exceptions able to escape the shared
  packet-dispatch loop or either relay's IO coroutine
- One Important finding (a blocking socket write running on whatever thread calls `TcpRelay.handle`,
  which a future shared drain thread would call from) deliberately deferred to #42 as a Ruling —
  not exploitable with zero live callers today, and the right fix (a bounded per-relay write queue)
  depends on #42's actual drain-loop threading model, which doesn't exist yet; same reasoning
  already used to defer `DnsResolverProvider`'s network-discovery mechanism and `isBlocked()`
  caching from #39/#40 into #42
- 41 new tests (144 total, up from 103): 3 `FlowKeyTest`, 8 `Ipv4TcpPacketTest`,
  7 `SessionTableTest`, 23 `TcpStateMachineTest`. `UdpRelay`/`TcpRelay`/`NatRelay`/`TunWriter` stay
  untested real-socket glue, same treatment as `InstalledAppsProvider`/`AnglerfishVpnService`
- Design spec: `.claude/sessions/specs/2026-10-02-nat-relay-engine-design.md`; plan:
  `.claude/sessions/plans/2026-10-02-nat-relay-engine.md`
tags: #nat #tcp #udp #networking #review
Ref: https://github.com/TomasGC/Anglerfish/issues/41
Commits: 132ab8f, 4cb0b63, 8f83a0b, abd88e5, 21c7cc2, ed75d12

---

2026-10-02 - [#40] DNS interception and resolution layer
- Standalone, fully testable DNS-interception component (`app.anglerfish.dns`): given a raw IPv4
  UDP packet, decides whether to synthesize an NXDOMAIN reply (domain on #39's blocklist) or
  forward the query to the device's real configured resolver and relay the answer back. Nothing
  wired into the live `AnglerfishVpnService` tunnel — explicit scope boundary matching #38/#39,
  since per-app `BlockMode` gating needs #38's data model and is #42's job
- `Ipv4UdpPacket` (pure, hand-rolled): parses/builds the IPv4/UDP envelope — fixed-size headers,
  no variable-length encoding, so no library justified. `DnsMessages` (pure, new `dnsjava`
  dependency): wraps DNS wire-format parsing/NXDOMAIN building — hand-rolling domain-name
  decompression is a well-known source of real bugs (compression-pointer loops are a classic CVE
  class), and `dnsjava` being pure JVM means this logic is plain-JUnit-testable with zero
  Android/Robolectric dependency
- `DnsForwarder`/`DnsResolverProvider` (interfaces + thin real impls, untested glue, same
  treatment as `InstalledAppsProvider`): forwarding socket calls `VpnService.protect()` before
  sending (the standard footgun — an unprotected socket's own packets re-enter the tunnel they're
  escaping) and `connect()`s to the resolver before `receive()` (rejects a reply from any other
  source, since an unconnected socket otherwise accepts the first datagram from anywhere on that
  ephemeral port)
- `DnsInterceptor` orchestrates all of the above behind one `suspend fun handle(raw): ByteArray?`
  — forwarding always targets the device's configured resolver regardless of the packet's own
  destination (so an app hardcoding its own DNS server can't bypass filtering), while the
  *response* packet's source address is the original packet's destination (what the app itself
  addressed), never the real resolver's address
- Fresh-reviewer final review (Opus) caught 1 Critical and 6 Important findings. Critical (missing
  `ACCESS_NETWORK_STATE` crashing every tunnel activation) traced back to a `setUnderlyingNetworks()`
  call added to existing #5 code during this issue's own Task 3 — removed entirely rather than
  patched, since `VpnService` exposes no way to read that value back anyway (the call achieved
  nothing for a future caller while also regressing live Wi-Fi/cellular handoff tracking), which
  reverted `AnglerfishVpnService.buildTunnel()` to its already-verified #5 shape with no new
  manual on-device testing needed. Other fixes: exceptions from `BlocklistRepository`/
  `DnsResolverProvider` no longer escape `handle()` (DataStore/`ConnectivityManager` failures now
  collapse to the documented `null`, not a crash); the forwarding-socket spoofing gap above;
  several Review-Focus test gaps (NXDOMAIN response shape, passthrough paths never invoking the
  forwarder, forwarded-path envelope assertions) closed with real test coverage rather than
  deferred
- `lintDebug` added to this issue's own verification loop after the review caught a manifest
  permission gap `detekt` alone doesn't check — same gap #39's review caught, now two-for-two on
  "detekt-only isn't CI-equivalent"
- 23 new tests (103 total, up from 80): 9 `Ipv4UdpPacketTest`, 4 `DnsMessagesTest`,
  10 `DnsInterceptorTest`
- Design spec: `.claude/sessions/specs/2026-10-02-dns-interception-design.md`; plan:
  `.claude/sessions/plans/2026-10-02-dns-interception.md`
tags: #vpn #dns #networking #review
Ref: https://github.com/TomasGC/Anglerfish/issues/40
Commits: 3ab639b, 15d99bc, 703b9ee, 053a55a

---

2026-10-01 - [#39] Ad-domain blocklist: remote refresh, user additions
- Two-layer blocklist (24h-refreshed remote cache + user additions), merged additions-only with no
  precedence conflicts — the data layer issue #40's DNS interception will consume. No new Gradle
  dependency: `HttpURLConnection` (via a `BlocklistFetcher` seam mirroring `VpnGateway`'s pattern),
  DataStore, a plain state-switch for navigation — Room/SQLite and Jetpack Navigation Compose both
  considered and rejected as disproportionate to two string sets and one new screen
- Originally designed with a third layer — a bundled asset snapshot of the full StevenBlack/hosts
  list, so first-launch-before-any-fetch still blocked something. Dropped after PR #45's CI run:
  the 2.2MB asset tripped the shared `condor` large-file gate (500KB cap, even gzip-9 only reached
  ~563KB). A trimmed bundled subset would have meant maintaining a second blocklist, so the layer
  was removed entirely instead — `AnglerfishApplication.onCreate()` already calls
  `refreshIfStale()` unconditionally with `lastFetch` defaulting to 0, so first launch already
  starts a real fetch immediately; coverage is just empty until that lands, rather than
  instant-but-stale from a committed snapshot
- `parseHostsFile`/`isDomainBlocked` (pure, `data/BlocklistParsing.kt`): hosts-file lines accepted
  only when sinked to `0.0.0.0` with a real (non-self-referential) domain token — rejects
  StevenBlack's own `127.0.0.1`/`::1` bootstrapping lines (which would otherwise block every
  `*.local` mDNS name once suffix matching applies) and malformed/HTML 200-responses alike;
  suffix-aware matching normalizes case and a trailing root dot before comparing labels
- `DataStoreBlocklistRepository`: `refreshIfStale` only overwrites the 24h remote cache when the
  fetch parses to something non-empty, so a captive-portal page or transient garbage can't silently
  erase a day of coverage; lives in its own `anglerfish_blocklist_prefs` DataStore file, separate
  from frequently-written app state, since Preferences DataStore rewrites its entire backing file
  on every edit
- `BlocklistScreen` (new, reached via `AppListScreen`'s overflow menu) + `BlocklistViewModel`:
  add/remove user-added domains, each lowercased and trimmed before persisting; `MainActivity`
  wires the system back button to return to the app list instead of exiting
- Fresh-reviewer final review (Opus) caught 3 Critical (Detekt `LongMethod`/`SwallowedException`/
  `MaxLineLength` — would have failed CI's lint-checks job) and 6 Important findings, all fixed in
  one pass, each verified RED→GREEN: missing back-navigation, the shared-DataStore and
  synchronous-asset-load issues above, and the strict-parsing/case-normalization work above (the
  lax-parsing gap traces back to a test-input fix made mid-Task-1, ledgered as a Ruling at the
  time, that the reviewer correctly flagged as hiding a real design gap rather than just a bad
  fixture). One finding (per-call re-parsing performance in `isBlocked`) deferred to #40 as a
  Ruling — nothing calls it live yet, and the right caching shape depends on #40's actual call
  pattern
- 31 new tests (80 total, up from 49): 15 `BlocklistParsingTest`, 11 `DataStoreBlocklistRepositoryTest`,
  5 `BlocklistViewModelTest`
- Design spec: `.claude/sessions/specs/2026-10-01-ad-domain-blocklist-design.md`; plan:
  `.claude/sessions/plans/2026-10-01-ad-domain-blocklist.md`
tags: #data #datastore #networking #ui #review
Ref: https://github.com/TomasGC/Anglerfish/issues/39
Commits: a32f0cb, f464ba5, acc62df

---

2026-10-01 - [#38] Per-app BlockMode: BlockingState data model
- `BlockMode` enum (`AdFilterOnly`/`FullBlock`) + `BlockingState.selectedPackages` changes from
  `Set<String>` to `Map<String, BlockMode>` — the foundation issue #28's domain-selective
  ad-filtering pivot depends on. No VPN/UI behavior change: `AnglerfishVpnService` still treats
  every selected package as full-block until #42 wires mode-aware dispatch
- `DataStoreAppRepository` persists the map as `"pkg:MODE"`-encoded entries under the existing
  `selected_packages` key (no new dependency); decode is self-migrating — a legacy bare
  package-name entry, or one with an unrecognized mode suffix, falls back to `FullBlock` keyed by
  the real package name, preserving a pre-existing install's current effective behavior rather
  than silently switching it. New `AppRepository.setMode()` clears the package from
  `HIDDEN_PACKAGES_KEY` in the same transaction, extending #33's mutual-exclusion invariant
- Final whole-branch review (fresh Opus reviewer) caught two real bugs before merge, both fixed
  with RED→GREEN tests: the unrecognized-suffix fallback was keying the entry by the *whole raw
  string* instead of the decoded package name — invisible, unremovable entry that, if it were the
  only selection, would build a tunnel with zero `addAllowedApplication` calls, which Android then
  applies device-wide; and `setMode` wasn't clearing hidden status, breaking #33's invariant for a
  caller #43 will add
- Tried a `ViewModel`-plus-real-`DataStoreAppRepository` integration test (only `VpnGateway`
  faked), motivated by `AppListViewModelTest`'s `FakeAppRepository` having already needed
  hand-syncing with real repository behavior twice in one session. Surfaced a real, independent bug
  along the way: a never-cancelled `CoroutineScope(SupervisorJob())` in both that test and the
  pre-existing `DataStoreAppRepositoryTest` left DataStore's write-actor coroutine alive past each
  test, reproducibly hanging a full-suite run on Windows (can't delete an open file) — fixed by
  cancelling the scope in `tearDown()` in both files, kept
- The `ViewModel`-plus-real-repository test itself was reverted after extensive debugging: in a bare
  JVM test (no Robolectric, no device), `viewModelScope` is fire-and-forget with no way to await or
  cancel it from test code, and `Dispatchers.setMain()` is built for fake/synchronous dependencies
  — pairing it with a real async one surfaced a genuine reentrancy hang
  (`Dispatchers.Unconfined` resuming inline on DataStore's own actor thread) and a cross-test leak
  (an orphaned coroutine throwing into an unrelated test once `TemporaryFolder` deleted its backing
  file). Replaced with `AppRepositoryContractTest` (abstract, 3 assertions) run against both
  `FakeAppRepositoryContractTest` and `DataStoreAppRepositoryContractTest` — gets the actual
  Fake/real-parity value without a `ViewModel` or `Dispatchers.Main` substitution anywhere in the
  loop; `FakeAppRepository` extracted to its own file so both the contract test and
  `AppListViewModelTest` share one implementation. Confirmed clean across 6 consecutive full-suite
  runs with live-streamed output (the `gradlew` wrapper's own buffering hid the first hang's cause)
- `contexts/tests.md`/`conventions.md`/`commands.md` updated: new count (49 automated tests total),
  the reverted-tier reasoning documented so it isn't retried blind, redundant raw-`gradlew`
  test/build examples removed in favor of `python scripts/manage.py test`/`build` as the standing
  preferred entrypoint
- Design spec: `.claude/sessions/specs/2026-09-30-domain-selective-blocking-design.md`; plan:
  `.claude/sessions/plans/2026-09-30-blockmode-data-model.md`. Six child issues under #28's tracking
  issue (#38–#43); this entry covers #38 only
tags: #data #datastore #testing #migration #review
Ref: https://github.com/TomasGC/Anglerfish/issues/38
Commits: fbe9baf, 398c458, 3736f3a, fd9ce2c, 164d5e3

---

2026-09-30 - [#33] Hide apps from the list, restructure into collapsible sections
- `BlockingState.hiddenPackages`, persisted via its own DataStore key; hiding a selected app
  deselects it in the same `dataStore.edit` transaction, unhiding never reselects it
- `AppListUiState`'s flat `apps` list becomes three buckets (`selectedApps`/`notSelectedApps`/
  `hiddenApps`), partitioned after the existing search filter; `toggleHidden()` only restarts/stops
  the VPN when hiding actually changed the selection, not on every hide
- `AppListScreen`: both list and grid layouts get three independently collapsible sections
  (`rememberSaveable`, survives rotation); swipe hides a Selected/Not-selected row or cell, tapping
  a Hidden one unhides it (same gesture as deselecting) — scope call made mid-implementation after
  the first on-device pass
- Three real bugs found and fixed, none caught by the unit suite alone (Compose UI has no
  automated tier here): on-device testing found `SwipeToDismissBox`'s background permanently
  visible at rest (only masks itself where the foreground paints opaque pixels, and the row/cell
  composables had none — same root cause also explained a white-rectangle artifact under some
  icons that first looked like a #30 regression); a fresh-context final review (Opus) against the
  whole branch then caught the swipe box coming back "already dismissed" after unhide, and
  `toggleHidden`'s VPN-restart guard reading the active flag before its own change instead of
  after, missing a concurrent-deactivate race
- Plan at `.claude/sessions/plans/2026-09-30-hide-apps-collapsible-sections.md`, executed inline;
  ledger kept at `.claude/sessions/superpowers/sdd/hide-apps-collapsible-sections/progress.md`
  (the skill's own `.superpowers/sdd/` default path is disallowed by this user's global instructions)
- 12 new tests: 5 `DataStoreAppRepositoryTest`, 7 `AppListViewModelTest`
tags: #ui #compose #data #persistence #swipe
Ref: https://github.com/TomasGC/Anglerfish/issues/33
Commits: a0cd3ff, 910df68, 952f170

---

2026-09-30 - [#32] Add a grid view toggle alongside the list view
- `AppListLayout` (`LIST`/`GRID`) is a new small enum in `data/`, persisted through
  `AppRepository`/`DataStoreAppRepository` as its own `stringPreferencesKey` rather than folded
  into `BlockingState` — layout is a rendering preference, not blocking state, and the two have no
  reason to change together; a corrupted or pre-migration stored value falls back to `LIST` instead
  of `enumValueOf` throwing
- `AppListViewModel.uiState` grows to a 4-way `combine` (state, installed apps, search query,
  layout); new `toggleLayout()` flips and persists it the same way `toggleApp` mutates selection
- `AppListScreen`: a `TopAppBar` `IconButton` swaps between a hand-drawn `ic_grid_view` vector
  (list mode, inviting a switch to grid) and the stock `Icons.Default.List` (grid mode) — checked
  the compiled `material-icons-core` jar directly and confirmed grid/list glyphs aren't in the core
  set bundled with material3, so a custom 4-square drawable was cheaper than pulling in the whole
  `material-icons-extended` artifact for one icon; body branches `LazyColumn` vs. a new
  `LazyVerticalGrid(GridCells.Fixed(2))` with a `AppGridItem` composable (64dp icon, name below,
  clickable, primary-color border as the selected-state affordance per the issue's "border, not
  necessarily a checkbox" option)
- Both layouts read the same `AppListUiState`/`AppListItem` and the active search filter with no
  special-casing, same pattern as #31's search landing cleanly under existing selection — verified
  by 4 new `AppListViewModelTest` cases (uiState reflects persisted layout, toggle persists,
  toggle-toggle returns to list, grid respects an active search query) plus 2
  `DataStoreAppRepositoryTest` cases (default, persistence)
- On-device verification: list → grid → select an app → search-filter in grid mode → force-stop →
  relaunch, confirming layout, selection and cleared search all land correctly on restart
tags: #ui #compose #grid #persistence
Ref: https://github.com/TomasGC/Anglerfish/issues/32
Commit: 96f5bf3

---

2026-09-29 - [#31] Add a search bar to filter the app list
- `AppListViewModel` gains a `searchQuery` `MutableStateFlow<String>`, combined into `uiState`
  alongside `repository.state` and the installed-apps list (3-way `combine`, not the 2-arg
  extension used before); apps filtered by case-insensitive label substring match before mapping
  to `AppListItem` — filtering lives here, not in `filterUserLaunchableApps`, since that stays
  about the fixed system/self-exclusion property of the installed-app set, not live UI state
- `AppListScreen`: `OutlinedTextField` above the list (search icon leading, clear icon trailing
  when non-empty), wired through a new `onSearchQueryChanged` callback threaded through
  `MainActivity`'s call site
- Selection state composes cleanly with the filter with no special-casing — toggling an app while
  filtered updates `BlockingState.selectedPackages` exactly like an unfiltered toggle, verified by
  3 new `AppListViewModelTest` cases (filter match, clearing restores the full list, toggle while
  filtered persists correctly)
tags: #ui #compose #search
Ref: https://github.com/TomasGC/Anglerfish/issues/31
Commit: 4b3d980

---

2026-09-29 - [#30] Display each app's icon next to its name in the list
- `InstalledAppsProvider.queryBlockableApps()` now loads each app's icon via
  `PackageManager.getApplicationIcon()` (`androidx.core:core-ktx`'s `Drawable.toBitmap()`),
  threaded through `InstalledApp` -> `AppListViewModel`'s `AppListItem` -> `AppRow`
- `icon` is nullable end to end (`InstalledApp`, `AppListItem`) — `null` on a
  `NameNotFoundException` during the icon lookup (e.g. a stale entry uninstalled between the
  launcher query and the icon call) falls back to blank spacing in the row rather than crashing
  the whole list over one app
- Existing tests (`AppListViewModelTest`) construct `InstalledApp` unchanged — `icon` defaults to
  `null`, and `Bitmap` isn't constructable outside a real Android runtime anyway
tags: #ui #compose #icons
Ref: https://github.com/TomasGC/Anglerfish/issues/30
Commit: 6a7f63e

---

2026-09-29 - [#25] Misc VpnService/lifecycle bugs found and fixed during real on-device verification
- Originally scoped as DNS fast-reject (a synthesized NXDOMAIN reply so blocked apps fail DNS
  lookups fast instead of retrying through timeouts) — built, unit-tested, then abandoned and
  removed entirely once real on-device use surfaced a scope mismatch in the underlying design, not
  in this issue's own code: full per-app blocking makes network-dependent apps (anything that
  needs a live connection just to render its UI, not only its ads) unusable, not just ad-free.
  What's actually wanted is closer to uBlock Origin — block known ad domains, forward everything
  else — which needs a real forwarding proxy, not a black-hole tunnel with a faster rejection
  reply. That's a different architecture, tracked separately (see #26 and the new proxy-pivot
  issue), so this issue stayed scoped to real bugs rather than growing to cover it
- Three genuine bugs surfaced and fixed along the way, independent of the DNS-reject work itself
  and still valid regardless of it:
  - **Consent race**: `MainActivity` fired the notification-permission prompt and the VPN-consent
    prompt from the same tap, concurrently. On a fresh install (new uid, so consent must be
    re-granted) the two overlapping system dialogs let one silently swallow the other's result —
    the switch showed active but `establishVpn()` was never called, leaving the selected app on
    real, unblocked internet. Root-caused from `adb logcat`: zero `establishVpn called by
    app.anglerfish` entries for the run where an ad loaded through "active" blocking. Fixed by
    resolving the notification prompt fully before activation proceeds
  - **Stale active state**: a hard kill (force-stop, swiping from recents) takes the VPN down
    with it, but bypasses `START_STICKY` and leaves DataStore's persisted `isActive` flag
    untouched — switch shows on, no tunnel running, nothing corrects it until manually toggled.
    Fixed by having `AppListViewModel` reconcile persisted state against the real VPN
    unconditionally on construction, reusing the same `vpnGateway.restart()` call `toggleApp`
    already makes for live selection changes
  - **Notification gaps**: tapping the persistent notification's body did nothing (no
    `setContentIntent`); tapping Stop tore the tunnel down but never persisted the inactive state
    (so the reconciliation fix above would silently re-establish it next launch); Stop left the
    rest of the single-process app running instead of quitting it. All three fixed together
- A fourth finding turned out to be general, not DNS-reject-specific: `input.read()` on the tun fd
  can return exactly `0` on this device even with nothing ever written back (confirmed via a
  control run with all writes disabled) — a device/driver-level quirk, not something caused by
  this issue's own code. A blocking read has no legitimate reason to return `0`; `drain()` now
  backs off briefly and retries instead of busy-spinning or giving up, which self-heals when the
  condition is transient (confirmed on-device: hundreds of real packets processed normally in
  bursts around brief backoff windows) and stays CPU-cheap even if it were ever sustained
- `docs/manual-testing.md` scenario 7 (process-death recovery) split into soft-kill/hard-kill/
  reconciliation sub-steps to cover the stale-active-state fix; the DNS fast-reject scenario was
  added then removed along with the feature itself
- These bugs only surfaced because they were verified for real, not just unit-tested — spun out
  into issue #27 (integration-mock/integration-real/instrumented test tiers), design spec at
  `.claude/sessions/specs/2026-09-29-test-tier-buildout-design.md`
tags: #vpn #bugfix #vpnservice #lifecycle
Ref: https://github.com/TomasGC/Anglerfish/issues/25
Commit: fe9e0d6

---

2026-09-28 - [#20] Fix: app list only showed a handful of OS-exempted apps on real devices
- Root cause: Android 11+ package visibility — `queryIntentActivities()` silently returns only a
  small OS-exempted set without a `<queries>` declaration; the user's actual installed apps
  (browser, social, games) were invisible to the query
- Fix: added `<queries><intent>` for `ACTION_MAIN`/`CATEGORY_LAUNCHER` to `AndroidManifest.xml`
  — the one query Android explicitly supports without the heavier, Play-Store-review-gated
  `QUERY_ALL_PACKAGES` permission
- No change to `filterUserLaunchableApps`/`isPureSystemApp` — that logic was correct all along,
  the apps just never reached it
tags: #bug #manifest #package-visibility
Ref: https://github.com/TomasGC/Anglerfish/issues/20

---

2026-09-26 - [#19] Python dev-tooling (manage.py) and CI/CD, ported from Raven/Otter
- `scripts/`: Python CLI (`manage.py build/test/validate/coverage/adb`) ported from Raven,
  adapted for a single-module project (no `-DtestType` tier filter yet)
- Kover wired in `app/build.gradle.kts` — report generation only, no hard threshold gate
  (`coverage-threshold: 0` in CI, interim — Raven's default 80 only works there because it has
  ~95% real coverage)
- Detekt wired in with Composable-aware overrides (`FunctionNaming`, `LongParameterList`) and a
  raised `ReturnCount` limit for guard-clause style; 3 real findings fixed in app code
- `.github/workflows/push-ci.yml` + `pr-ci.yml` via `TomasGC/condor`'s reusable workflows;
  `.osv-scanner.toml` copied from Raven (same AGP/Gradle-internal build-tool dependency overrides
  apply — identical version family)
- Two real bugs found and fixed along the way: `gradlew`'s wrapper checksum was byte-for-byte
  correct but uppercase (Gradle's comparison is case-sensitive, fails 100% of the time), and
  `gradlew` had lost its executable bit
- `gradle/verification-metadata.xml` regenerated to cover both Windows and Linux
  platform-specific artifacts (`aapt2`) plus Detekt/Lint/Kover's own tool dependencies —
  `--write-verification-metadata` is additive, so running it again on a different OS adds that
  OS's entries without needing a hand merge
tags: #ci #python #detekt #kover #tooling
Ref: https://github.com/TomasGC/Anglerfish/issues/19
PR: https://github.com/TomasGC/Anglerfish/pull/21

---

2026-09-21 - [#9] Docs refresh: README, .claude/, wiki
- Original issue #9 scope (commit `.claude/` to git) already happened as a side effect of the
  per-issue docs-commit convention started at #5 — this issue became a final wrap-up pass instead
- `tests.md` counts confirmed real (not "target"): 4+4+7 = 15 unit tests, verified via
  `./gradlew testDebugUnitTest` JUnit XML reports
- Added root `README.md` (project overview, build instructions, doc links) — didn't exist before
- Fixed 3 wiki pages that still referenced the pre-renumbering issue scheme and wrongly claimed
  `.claude/` was gitignored
tags: #docs #wrap-up
Ref: https://github.com/TomasGC/Anglerfish/issues/9
PR: https://github.com/TomasGC/Anglerfish/pull/18
Commit: 8f88916

---

2026-09-21 - [#8] Manual VpnService verification checklist
- `docs/manual-testing.md`: 8 on-device scenarios (first-run consent, activate/deactivate,
  notification deactivate action, empty-selection guard, live restart on selection change,
  unselect-last-app auto-stop, process-death recovery, establish() failure path)
- Docs-only, no code — the substitute for automated coverage of `vpn/`, which
  `VpnService.Builder.establish()` makes impossible to unit test
- Correction: `.claude/contexts/conventions.md` clarified that `docs:` commits never carry an
  issue number, not even in prose — the controller initially got this wrong by reasoning from
  Raven's real (inconsistent) git history instead of Anglerfish's own stated convention
tags: #docs #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/8
PR: https://github.com/TomasGC/Anglerfish/pull/17
Commit: 0885f82

---

2026-09-21 - [#7] Compose UI wiring
- `AppListScreen`: top-bar Activate/Deactivate switch, `LazyColumn` of apps with checkboxes,
  snackbar for the empty-selection guard
- `MainActivity` fully replaced (was #1's placeholder): builds the ViewModel via
  `AnglerfishApplication.container`, wires the VPN consent `ActivityResultLauncher` (on
  `RESULT_OK` calls `onConsentGranted()` directly — never re-checks consent by calling
  `onActivateClicked()` again, which would lose the original activation intent), requests the
  Android 13+ notification permission before activating (non-blocking on denial)
- App is now feature-complete for v1's core loop: browse, select, activate/deactivate, persist
  across restart, foreground notification
tags: #ui #compose #consent-flow
Ref: https://github.com/TomasGC/Anglerfish/issues/7
PR: https://github.com/TomasGC/Anglerfish/pull/16
Commit: 8dfb78d

---

2026-09-21 - [#6] AppListViewModel
- `AppListUiState`/`AppListItem`/`AppListEvent` (`SelectionEmpty`, `VpnConsentRequired`) +
  `AppListViewModel`: `toggleApp`, `onActivateClicked`, `onConsentGranted`, `onDeactivateClicked`
- Empty-selection guard on activate; consent-needed branch defers start to a separate
  `onConsentGranted()` call; unselecting the last active app stops instead of restarting with an
  empty set; toggling while active restarts with the full current selection (not just the
  toggled package)
- 7 unit tests against `FakeAppRepository`/`FakeVpnGateway`, no real `Context`/`VpnService`/
  DataStore needed
- Task review caught a real gap: the "toggle while active" test's original single-app fixture
  couldn't distinguish "restart with full set" from a hypothetical "restart with just the
  toggled package" bug — fixed by strengthening the fixture to 2 apps (see the SDD ledger)
tags: #viewmodel #mvvm #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/6
PR: https://github.com/TomasGC/Anglerfish/pull/15
Commits: 04e7a42, 34b131e

---

2026-09-21 - [#5] AnglerfishVpnService mechanic
- `AnglerfishVpnService`: `Builder().addAllowedApplication(pkg)` per selected package,
  `establish()`, background drain thread discards every packet with nothing written back;
  rebuilds the tunnel from the current selection on every start (supports auto-restart on
  selection change while active)
- Foreground notification (with Deactivate action) + `START_STICKY`; `establish()` failure and
  `onRevoke()` both reset `AppRepository`'s active flag and surface a toast, never crash
- `AppContainer` (manual DI) + the real `AnglerfishApplication` that owns it, replacing #1's
  placeholder
- Adapted from a read-only reference in Raven (`app.raven.vpn.AdBlockVpnService`) — no Raven
  code imported; the allow-list is rebuilt from the current selection every start, not
  hardcoded to one package
- Not unit tested (`VpnService.Builder.establish()` needs a real/emulated device) — manual
  verification checklist lands in #8
tags: #vpn #mechanic #foreground-service
Ref: https://github.com/TomasGC/Anglerfish/issues/5
PR: https://github.com/TomasGC/Anglerfish/pull/14
Commit: e54b56a

---

2026-09-21 - [#4] VPN gateway seam
- `vpnConsentIntent()`, `VpnGateway` interface, `VpnController` — real `Context`-based
  implementation
- `VpnGateway` exists purely so #6's ViewModel can be unit-tested without a real
  `Context`/`VpnService`; includes a compile-only `AnglerfishVpnService` stub until #5 replaces it
tags: #vpn #testability-seam
Ref: https://github.com/TomasGC/Anglerfish/issues/4
PR: https://github.com/TomasGC/Anglerfish/pull/13
Commit: 62c0cb8

---

2026-09-21 - [#3] Persistence layer
- `InstalledAppsProvider` (`PackageManager` glue, not unit tested), `BlockingState`,
  `AppRepository` interface, `DataStoreAppRepository` (Preferences DataStore)
- DataStore tested purely on the JVM via `PreferenceDataStoreFactory` + `TemporaryFolder` — no
  device/Robolectric needed
tags: #data #datastore #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/3
PR: https://github.com/TomasGC/Anglerfish/pull/12
Commit: 283cdc1

---

2026-09-21 - [#2] App-list domain model and filtering
- `InstalledApp` domain model, `AppCandidate` + pure `filterUserLaunchableApps` (excludes pure
  system apps and Anglerfish itself)
- 4 unit tests, no `PackageManager` dependency in this code (kept pure for testability)
tags: #data #testing
Ref: https://github.com/TomasGC/Anglerfish/issues/2
PR: https://github.com/TomasGC/Anglerfish/pull/11
Commit: d344540

---

2026-09-21 - [#1] Project scaffolding
- Kotlin + Compose skeleton, package `app.anglerfish`, minSdk 26 / target+compile SDK 36, Gradle
  version catalog, MVVM package layout
- Gradle wrapper regenerated from the official distribution with checksum verification (found
  during task review — the first-drafted wrapper jar had no provenance verification)
tags: #scaffolding #gradle
Ref: https://github.com/TomasGC/Anglerfish/issues/1
PR: https://github.com/TomasGC/Anglerfish/pull/10
Commits: 3d609ee, 0d520f3
