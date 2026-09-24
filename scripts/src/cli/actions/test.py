"""Test action — run the Kotlin test suite.

Anglerfish's app/build.gradle.kts has no -DtestType Gradle property filter (unlike Raven's,
which splits unit/integration-mock/integration-real JVM tests by class-name suffix) — the
app has exactly one JVM test tier today (plain app/src/test/, see contexts/tests.md). The
unit/integration-mock/integration-real suite-selection CLI surface is kept so this interface
doesn't need to change again once Anglerfish actually grows a second tier; until then, all
three suites run the same untiered testDebugUnitTest task. Add the -DtestType filter to
app/build.gradle.kts in a future issue if/when a second tier is introduced.
"""

import os
from pathlib import Path
from typing import Optional

from android import AdbManager, GradleRunner
from common.file_utils import get_project_root
from common.subprocess_runner import SubprocessRunner

SUITES = ["unit", "integration-mock", "integration-real", "instrumented"]


class TestAction:
    def __init__(
        self,
        runner: SubprocessRunner,
        gradle: Optional[GradleRunner] = None,
        adb: Optional[AdbManager] = None,
        project_root: Optional[Path] = None,
    ) -> None:
        self._runner = runner
        self._project_root = project_root or get_project_root()
        self._gradle = gradle or GradleRunner(runner, self._project_root)
        self._adb = adb or AdbManager(runner)

    def run_all_untiered(self) -> bool:
        # The plain `test` command (no suite named) — this is the one that actually matters
        # today. Single Gradle module, so :app's own testDebugUnitTest is the whole suite.
        return self._gradle.run_task("testDebugUnitTest")

    def run_unit(self) -> bool:
        # No -DtestType filter exists yet — see module docstring. unit/integration-mock/
        # integration-real all alias to the same untiered task until a second tier lands.
        return self.run_all_untiered()

    def run_integration_mock(self) -> bool:
        return self.run_all_untiered()

    def run_integration_real(self) -> bool:
        return self.run_all_untiered()

    def run_instrumented(self, device: str) -> bool:
        os.environ["ANDROID_SERIAL"] = device
        try:
            return self._gradle.run_task("connectedDebugAndroidTest", timeout=900)
        finally:
            os.environ.pop("ANDROID_SERIAL", None)

    def run(self, suites: list[str] | None = None) -> int:
        suites = suites or []

        if not suites:
            return 0 if self.run_all_untiered() else 1

        success = True

        if "unit" in suites:
            if not self.run_unit():
                success = False

        if "integration-mock" in suites:
            if not self.run_integration_mock():
                success = False

        if "integration-real" in suites:
            if not self.run_integration_real():
                success = False

        if "instrumented" in suites:
            devices = self._adb.get_connected()
            if not devices:
                device = self._ensure_emulator()
                if not device:
                    print("No device connected for instrumented tests")
                    success = False
                elif not self.run_instrumented(device):
                    success = False
            elif not self.run_instrumented(devices[0]):
                success = False

        return 0 if success else 1

    def _ensure_emulator(self) -> str | None:
        emulators = self._adb.get_running_emulators()
        if emulators:
            print(f"Found running emulator: {emulators[0]}, waiting for ready state...")
            return self._adb.wait_for_emulator()
        avds = self._adb.list_avds()
        if not avds:
            print("No AVD found — create one in Android Studio")
            return None
        print(f"Starting emulator: {avds[0]}")
        if not self._adb.start_emulator(avds[0]):
            return None
        return self._adb.wait_for_emulator()
