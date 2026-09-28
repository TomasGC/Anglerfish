#!/usr/bin/env python3
"""Integration-real tests for AdbManager — real adb subprocess, no mocks.

Exercises the real, locally installed Android SDK / adb / emulator directly.
No FakeSubprocessRunner here — that's the entire point of this tier.
"""

import pytest

from android.adb import AdbManager
from common.subprocess_runner import RealSubprocessRunner

pytestmark = pytest.mark.integration_real


class TestAdbManagerReal:
    @pytest.mark.local_only
    def test_is_available_reflects_adb_install(self):
        # Requires adb in PATH — not installed on every CI runner.
        assert AdbManager(RealSubprocessRunner()).is_available() is True

    def test_list_avds_returns_list_without_crash(self):
        result = AdbManager(RealSubprocessRunner()).list_avds()
        assert isinstance(result, list)
        for avd in result:
            assert isinstance(avd, str) and avd.strip()

    @pytest.mark.local_only
    def test_list_avds_finds_the_local_avd(self):
        # Confirms the real Android SDK on this machine actually has at least
        # one AVD configured — otherwise every instrumented test run would
        # silently have nothing to auto-boot against.
        result = AdbManager(RealSubprocessRunner()).list_avds()
        assert len(result) >= 1

    @pytest.mark.local_only
    def test_get_running_emulators_returns_list_without_crash(self):
        result = AdbManager(RealSubprocessRunner()).get_running_emulators()
        assert isinstance(result, list)
        for emu in result:
            assert emu.startswith("emulator-")
