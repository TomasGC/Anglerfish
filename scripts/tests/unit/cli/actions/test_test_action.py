"""Tests for cli.actions.test.TestAction.

The JVM suites (unit, integration-mock, integration-real) each run the same JVM task set
(JVM_TASKS: :core tiers + :app unit tests). Named together they run that set once.
"""

from pathlib import Path
from typing import Optional

from cli.actions.test import JVM_TASKS
from cli.actions.test import TestAction as ActionUnderTest
from tests.helpers.fake_subprocess import FakeSubprocessRunner

JVM_CALLS = [(task, 600, ()) for task in JVM_TASKS]


class FakeGradleRunner:
    def __init__(self, succeeds: bool = True) -> None:
        self.succeeds = succeeds
        self.calls: list[tuple] = []

    def run_task(self, task: str, timeout: int = 600, extra_args=None) -> bool:
        self.calls.append((task, timeout, tuple(extra_args or [])))
        return self.succeeds


class FakeAdbManager:
    def __init__(
        self,
        connected: Optional[list[str]] = None,
        running_emulators: Optional[list[str]] = None,
        avds: Optional[list[str]] = None,
        emulator_boots_to: Optional[str] = None,
    ) -> None:
        self._connected = connected or []
        self._running_emulators = running_emulators or []
        self._avds = avds or []
        self._emulator_boots_to = emulator_boots_to

    def get_connected(self) -> list[str]:
        return self._connected

    def get_running_emulators(self) -> list[str]:
        return self._running_emulators

    def list_avds(self) -> list[str]:
        return self._avds

    def start_emulator(self, avd_name: str) -> bool:
        return True

    def wait_for_emulator(self, timeout: int = 180) -> Optional[str]:
        return self._emulator_boots_to


def make_action(tmp_path: Path, gradle: FakeGradleRunner, adb: Optional[FakeAdbManager] = None) -> ActionUnderTest:
    return ActionUnderTest(
        runner=FakeSubprocessRunner(),
        gradle=gradle,
        adb=adb or FakeAdbManager(),
        project_root=tmp_path,
    )


def test_run_with_no_suites_runs_every_jvm_task_in_order(tmp_path: Path) -> None:
    # Arrange — no-suites-given runs the full JVM set: both core tiers and the app unit tests.
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=[])

    # Assert
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_with_none_suites_also_runs_every_jvm_task(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=None)

    # Assert
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_includes_core_tiers_and_app_unit_tests() -> None:
    # Arrange / Act / Assert — the set itself: pure JVM tiers plus the Android unit task.
    assert JVM_TASKS == [
        ":core:test",
        ":core:integrationMock",
        ":core:integrationReal",
        ":app:testDebugUnitTest",
    ]


def test_run_all_jvm_failure_returns_nonzero(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=False)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=[])

    # Assert
    assert exit_code == 1


def test_run_dispatches_unit_suite(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=["unit"])

    # Assert
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_dispatches_integration_mock_suite(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=["integration-mock"])

    # Assert
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_dispatches_integration_real_suite(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=["integration-real"])

    # Assert
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_jvm_suites_named_together_run_the_set_once(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=["unit", "integration-mock", "integration-real"])

    # Assert — one pass over the JVM set, not one per named suite
    assert exit_code == 0
    assert gradle.calls == JVM_CALLS


def test_run_dispatches_instrumented_suite_when_device_connected(tmp_path: Path) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    adb = FakeAdbManager(connected=["emulator-5554"])
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    exit_code = action.run(suites=["instrumented"])

    # Assert
    assert exit_code == 0
    assert gradle.calls == [("connectedDebugAndroidTest", 900, ())]


def test_run_instrumented_suite_boots_emulator_when_none_connected(tmp_path: Path) -> None:
    # Arrange — no device connected, but an AVD exists and boots successfully
    gradle = FakeGradleRunner(succeeds=True)
    adb = FakeAdbManager(connected=[], avds=["Pixel_7_API_34"], emulator_boots_to="emulator-5554")
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    exit_code = action.run(suites=["instrumented"])

    # Assert — falls back to auto-boot, then runs against the now-ready emulator
    assert exit_code == 0
    assert gradle.calls == [("connectedDebugAndroidTest", 900, ())]


def test_run_instrumented_suite_fails_cleanly_when_no_avd_exists(tmp_path: Path, capsys) -> None:
    # Arrange — no device connected, no AVD to fall back to either
    gradle = FakeGradleRunner(succeeds=True)
    adb = FakeAdbManager(connected=[], avds=[])
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    exit_code = action.run(suites=["instrumented"])

    # Assert
    assert exit_code == 1
    assert "No device connected for instrumented tests" in capsys.readouterr().out
    assert gradle.calls == []


def test_run_instrumented_suite_fails_cleanly_when_emulator_never_boots(tmp_path: Path, capsys) -> None:
    # Arrange — an AVD exists and "starts", but never reaches a ready state in time
    gradle = FakeGradleRunner(succeeds=True)
    adb = FakeAdbManager(connected=[], avds=["Pixel_7_API_34"], emulator_boots_to=None)
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    exit_code = action.run(suites=["instrumented"])

    # Assert
    assert exit_code == 1
    assert "No device connected for instrumented tests" in capsys.readouterr().out
    assert gradle.calls == []


def test_run_instrumented_suite_fails_cleanly_with_no_device(tmp_path: Path, capsys) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    adb = FakeAdbManager(connected=[])
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    exit_code = action.run(suites=["instrumented"])

    # Assert — must fail cleanly (no crash) and never invoke gradle at all
    assert exit_code == 1
    assert "No device connected for instrumented tests" in capsys.readouterr().out
    assert gradle.calls == []


def test_run_jvm_and_instrumented_runs_all_and_fails_if_any_fails(tmp_path: Path) -> None:
    # Arrange — the first JVM task succeeds and the rest fail: overall result must be failure,
    # and every JVM task still runs (not short-circuited after the first failure).
    class SequentialGradleRunner(FakeGradleRunner):
        def __init__(self) -> None:
            super().__init__(succeeds=True)
            self._call_index = 0

        def run_task(self, task, timeout=600, extra_args=None):
            super().run_task(task, timeout, extra_args)
            self._call_index += 1
            return self._call_index == 1

    gradle = SequentialGradleRunner()
    action = make_action(tmp_path, gradle)

    # Act
    exit_code = action.run(suites=["unit", "integration-mock"])

    # Assert
    assert exit_code == 1
    assert len(gradle.calls) == len(JVM_TASKS)


def test_run_instrumented_sets_and_clears_android_serial_env_var(tmp_path: Path, monkeypatch) -> None:
    # Arrange
    import os

    monkeypatch.delenv("ANDROID_SERIAL", raising=False)
    observed_serial = {}

    class RecordingGradleRunner(FakeGradleRunner):
        def run_task(self, task, timeout=600, extra_args=None):
            observed_serial["value"] = os.environ.get("ANDROID_SERIAL")
            return super().run_task(task, timeout, extra_args)

    gradle = RecordingGradleRunner()
    adb = FakeAdbManager(connected=["emulator-5554"])
    action = make_action(tmp_path, gradle, adb=adb)

    # Act
    action.run(suites=["instrumented"])

    # Assert
    assert observed_serial["value"] == "emulator-5554"
    assert "ANDROID_SERIAL" not in os.environ
