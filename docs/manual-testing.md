# Manual VpnService Verification Checklist

`VpnService.Builder.establish()` cannot be exercised under Robolectric or a JVM unit test — run
this checklist on a real device or emulator (API 26+) after any change touching `vpn/` or the
activation flow in `AppListViewModel`/`MainActivity`.

## 1. First-run consent

1. Fresh install, select one app, tap the top-bar switch to activate.
2. **Expect:** Android's system VPN consent dialog appears.
3. Tap "OK".
4. **Expect:** the switch turns on, a persistent notification "Anglerfish is blocking internet
   access" appears, and the selected app's network calls start timing out.
5. Force-stop and reopen Anglerfish, activate again.
6. **Expect:** no consent dialog this time (Android remembered it) — blocking starts immediately.

## 2. Basic activate/deactivate

1. With one app selected and blocking active, open that app and confirm it has no internet
   (e.g. a page load times out).
2. Deactivate from the app's top-bar switch.
3. **Expect:** the notification disappears immediately and the app regains internet within a
   few seconds (retry the same page load).

## 3. Deactivate from the notification

1. Activate blocking.
2. Pull down the notification shade, tap "Deactivate" on the Anglerfish notification.
3. **Expect:** the notification disappears, and reopening Anglerfish shows the switch as off.

## 4. Empty-selection guard

1. Deselect every app.
2. Tap the top-bar switch to activate.
3. **Expect:** a snackbar message appears ("Select at least one app before activating"), the
   switch stays off, and no notification appears.

## 5. Live restart on selection change while active

1. Select two apps, activate.
2. While still active, select a third app.
3. **Expect:** within roughly a second, that third app also loses internet access, and the two
   original apps remain blocked throughout (a brief reconnect blip on them is expected and fine).
4. While still active, deselect one of the three apps.
5. **Expect:** that app regains internet access within a few seconds; the others stay blocked.

## 6. Unselecting the last active app

1. With exactly one app selected and blocking active, deselect it.
2. **Expect:** blocking turns off automatically (switch flips off, notification disappears) —
   the service does not stay running with an empty allow-list.

## 7. Process-death recovery

1. Select one app, activate.
2. `adb shell am kill app.anglerfish` (soft kill — respects `START_STICKY`).
3. **Expect:** the notification remains (or briefly reappears) and the selected app is still
   blocked without reopening Anglerfish at all.
4. Reactivate, then `adb shell am force-stop app.anglerfish` (hard kill — bypasses
   `START_STICKY`; also what a user swiping the app away from recents triggers).
5. **Expect:** the notification and tunnel are gone immediately — the app is *not* blocked at
   this point, and the switch still shows on (persisted state survives the kill even though the
   real tunnel doesn't).
6. Reopen Anglerfish.
7. **Expect:** blocking resumes automatically on launch, with no manual toggle needed — the
   ViewModel reconciles the persisted "active" state against the real VPN as soon as it's
   constructed. Before this was fixed, the switch stayed stuck showing "on" while the selected
   app silently had full, unblocked internet until manually toggled off and back on.

## 8. establish() failure path

1. Install and activate a different, unrelated VPN app so it holds the system's VPN slot.
2. In Anglerfish, select an app and try to activate.
3. **Expect:** either Android's consent flow forces a slot switch (common on most OEMs, in which
   case blocking proceeds normally and this scenario can't be triggered this way), or Anglerfish
   shows the "Couldn't start blocking" toast and the switch stays off — never a crash.
