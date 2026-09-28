"""Tests for cli.actions.build.BuildAction."""

from pathlib import Path
from typing import Optional

from cli.actions.build import BuildAction
from tests.helpers.fake_subprocess import FakeSubprocessRunner


class FakeGradleRunner:
    """Records run_task calls and returns a canned success/failure result."""

    def __init__(self, succeeds: bool = True) -> None:
        self.succeeds = succeeds
        self.tasks_run: list[str] = []

    def run_task(self, task: str, timeout: int = 600, extra_args=None) -> bool:
        self.tasks_run.append(task)
        return self.succeeds


class FakeAdbManager:
    """Records install_apk calls; connected devices and install result are canned."""

    def __init__(self, connected: Optional[list[str]] = None, install_succeeds: bool = True) -> None:
        self._connected = connected or []
        self._install_succeeds = install_succeeds
        self.install_calls: list[tuple] = []

    def get_connected(self) -> list[str]:
        return self._connected

    def install_apk(self, apk_path: Path, device: Optional[str] = None) -> bool:
        self.install_calls.append((apk_path, device))
        return self._install_succeeds


class FakeVersionManager:
    """Records increment()/get_apk_path() calls with canned results."""

    def __init__(
        self,
        increment_result: Optional[tuple] = (2, "1.0.2"),
        increment_raises: Optional[Exception] = None,
        apk_path: Optional[Path] = None,
    ) -> None:
        self._increment_result = increment_result
        self._increment_raises = increment_raises
        self._apk_path = apk_path

    def increment(self):
        if self._increment_raises:
            raise self._increment_raises
        return self._increment_result

    def get_apk_path(self, variant: str = "debug"):
        return self._apk_path


def make_action(
    tmp_path: Path,
    gradle: Optional[FakeGradleRunner] = None,
    adb: Optional[FakeAdbManager] = None,
    version_mgr: Optional[FakeVersionManager] = None,
) -> BuildAction:
    return BuildAction(
        runner=FakeSubprocessRunner(),
        gradle=gradle or FakeGradleRunner(succeeds=True),
        adb=adb or FakeAdbManager(),
        version_mgr=version_mgr or FakeVersionManager(),
        project_root=tmp_path,
    )


def test_run_aborts_when_version_increment_fails(tmp_path: Path, capsys) -> None:
    # Arrange
    version_mgr = FakeVersionManager(increment_raises=ValueError("no build.gradle.kts"))
    action = make_action(tmp_path, version_mgr=version_mgr)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    assert "Failed to increment version" in capsys.readouterr().out


def test_run_aborts_when_build_fails(tmp_path: Path, capsys) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=False)
    action = make_action(tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    assert "Build failed" in capsys.readouterr().out
    assert gradle.tasks_run == ["assembleDebug"]


def test_run_aborts_when_apk_not_found(tmp_path: Path, capsys) -> None:
    # Arrange
    version_mgr = FakeVersionManager(apk_path=None)
    action = make_action(tmp_path, version_mgr=version_mgr)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    assert "APK not found after build" in capsys.readouterr().out


def test_run_skips_install_with_no_install_flag(tmp_path: Path, capsys) -> None:
    # Arrange
    apk_path = tmp_path / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_path.parent.mkdir(parents=True)
    apk_path.write_bytes(b"fake apk")
    adb = FakeAdbManager()
    version_mgr = FakeVersionManager(apk_path=apk_path)
    action = make_action(tmp_path, adb=adb, version_mgr=version_mgr)

    # Act
    exit_code = action.run(install=False)

    # Assert
    assert exit_code == 0
    assert "Build complete (install skipped)" in capsys.readouterr().out
    assert adb.install_calls == []


def test_run_prints_manual_install_message_when_no_device(tmp_path: Path, capsys) -> None:
    # Arrange
    apk_path = tmp_path / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_path.parent.mkdir(parents=True)
    apk_path.write_bytes(b"fake apk")
    adb = FakeAdbManager(connected=[])
    version_mgr = FakeVersionManager(apk_path=apk_path)
    action = make_action(tmp_path, adb=adb, version_mgr=version_mgr)

    # Act
    exit_code = action.run(install=True)

    # Assert
    assert exit_code == 0
    out = capsys.readouterr().out
    assert "No device connected" in out
    assert "Install manually: adb install -r" in out
    assert adb.install_calls == []


def test_run_aborts_when_install_fails(tmp_path: Path, capsys) -> None:
    # Arrange
    apk_path = tmp_path / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_path.parent.mkdir(parents=True)
    apk_path.write_bytes(b"fake apk")
    adb = FakeAdbManager(connected=["emulator-5554"], install_succeeds=False)
    version_mgr = FakeVersionManager(apk_path=apk_path)
    action = make_action(tmp_path, adb=adb, version_mgr=version_mgr)

    # Act
    exit_code = action.run(install=True)

    # Assert
    assert exit_code == 1
    assert "Installation failed" in capsys.readouterr().out


def test_run_success_path_installs_on_first_device(tmp_path: Path, capsys) -> None:
    # Arrange
    apk_path = tmp_path / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_path.parent.mkdir(parents=True)
    apk_path.write_bytes(b"fake apk")
    adb = FakeAdbManager(connected=["emulator-5554", "emulator-5556"], install_succeeds=True)
    version_mgr = FakeVersionManager(apk_path=apk_path)
    action = make_action(tmp_path, adb=adb, version_mgr=version_mgr)

    # Act
    exit_code = action.run(install=True)

    # Assert
    assert exit_code == 0
    assert "App installed successfully" in capsys.readouterr().out
    assert adb.install_calls == [(apk_path, "emulator-5554")]


def test_build_apk_capitalizes_variant() -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = BuildAction(
        runner=FakeSubprocessRunner(),
        gradle=gradle,
        adb=FakeAdbManager(),
        version_mgr=FakeVersionManager(),
        project_root=Path("/project"),
    )

    # Act
    action.build_apk(variant="release")

    # Assert
    assert gradle.tasks_run == ["assembleRelease"]
