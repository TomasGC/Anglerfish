# Anglerfish

Android app that lets you pick specific installed apps and fully cut their network access on or
off with a single toggle.

Not a real VPN — no server, no account, no cloud sync, no DNS/domain filtering. Everything runs
and stays on-device. Selected apps' internet calls simply time out, exactly like the phone has
no connection, while every other app keeps working normally.

## How it works

Android's `VpnService.Builder().addAllowedApplication(pkg)` creates a virtual network interface
scoped to whichever apps you've selected. Anglerfish never reads or forwards a single packet — it
just discards everything and writes nothing back, which is indistinguishable from "no internet"
to the blocked apps.

## Requirements

- Android 8.0 (API 26) or newer
- Android Studio / JDK 17 to build from source

## Building

```bash
git clone https://github.com/TomasGC/Anglerfish.git
cd Anglerfish
./gradlew assembleDebug
```

## Documentation

- [Wiki](https://github.com/TomasGC/Anglerfish/wiki) — product requirements, architecture,
  development conventions
- [`docs/manual-testing.md`](docs/manual-testing.md) — on-device verification checklist for the
  VPN mechanic (can't be unit tested — see the wiki's Architecture page for why)
