"""Test action — run the Kotlin test suite.

The JVM suites are the Gradle test tasks of both JVM modules: :core (test, integrationMock,
integrationReal — pure JVM, no Android) and :app (testDebugUnitTest — Android unit tests).
`unit`, `integration-mock` and `integration-real` each select the whole JVM set, which runs once
however many of them are named. `instrumented` runs connectedDebugAndroidTest on a device or
emulator.
"""

import os
from pathlib import Path
from typing import Optional

from android import AdbManager, GradleRunner
from common.file_utils import get_project_root
from common.subprocess_runner import SubprocessRunner

SUITES = ["unit", "integration-mock", "integration-real", "instrumented"]
JVM_SUITES = {"unit", "integration-mock", "integration-real"}
JVM_TASKS = [
    ":core:test",
    ":core:integrationMock",
    ":core:integrationReal",
    ":app:testDebugUnitTest",
]


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

    def run_jvm_suites(self) -> bool:
        # Every task runs even after a failure, so one run reports all failing tiers.
        results = [self._gradle.run_task(task) for task in JVM_TASKS]
        return all(results)

    def run_instrumented(self, device: str) -> bool:
        os.environ["ANDROID_SERIAL"] = device
        try:
            return self._gradle.run_task("connectedDebugAndroidTest", timeout=900)
        finally:
            os.environ.pop("ANDROID_SERIAL", None)

    def run(self, suites: list[str] | None = None) -> int:
        suites = suites or []

        if not suites:
            return 0 if self.run_jvm_suites() else 1

        success = True

        if JVM_SUITES.intersection(suites):
            if not self.run_jvm_suites():
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
