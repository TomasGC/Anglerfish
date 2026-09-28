"""Tests for cli.manage.Manager — subcommand dispatch."""

import pytest

from cli.manage import Manager
from tests.helpers.fake_subprocess import FakeSubprocessRunner


def test_dispatch_build_routes_to_build_action(monkeypatch) -> None:
    # Arrange
    captured = {}

    class FakeBuildAction:
        def __init__(self, runner):
            captured["runner"] = runner

        def run(self, install):
            captured["install"] = install
            return 0

    monkeypatch.setattr("cli.manage.BuildAction", FakeBuildAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    exit_code = manager.dispatch(["build"])

    # Assert
    assert exit_code == 0
    assert captured["install"] is True


def test_dispatch_build_no_install_flag(monkeypatch) -> None:
    # Arrange
    captured = {}

    class FakeBuildAction:
        def __init__(self, runner):
            pass

        def run(self, install):
            captured["install"] = install
            return 0

    monkeypatch.setattr("cli.manage.BuildAction", FakeBuildAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    manager.dispatch(["build", "--no-install"])

    # Assert
    assert captured["install"] is False


def test_dispatch_test_without_suites(monkeypatch) -> None:
    # Arrange
    captured = {}

    class FakeTestAction:
        def __init__(self, runner):
            pass

        def run(self, suites):
            captured["suites"] = suites
            return 0

    monkeypatch.setattr("cli.manage.TestAction", FakeTestAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    exit_code = manager.dispatch(["test"])

    # Assert
    assert exit_code == 0
    assert captured["suites"] == []


def test_dispatch_test_with_suites(monkeypatch) -> None:
    # Arrange
    captured = {}

    class FakeTestAction:
        def __init__(self, runner):
            pass

        def run(self, suites):
            captured["suites"] = suites
            return 0

    monkeypatch.setattr("cli.manage.TestAction", FakeTestAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    manager.dispatch(["test", "unit", "integration-mock"])

    # Assert
    assert captured["suites"] == ["unit", "integration-mock"]


def test_dispatch_validate_routes_to_validate_action(monkeypatch) -> None:
    # Arrange
    calls = []

    class FakeValidateAction:
        def __init__(self, runner):
            pass

        def run(self):
            calls.append("ran")
            return 0

    monkeypatch.setattr("cli.manage.ValidateAction", FakeValidateAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    exit_code = manager.dispatch(["validate"])

    # Assert
    assert exit_code == 0
    assert calls == ["ran"]


def test_dispatch_coverage_routes_to_coverage_action(monkeypatch) -> None:
    # Arrange
    calls = []

    class FakeCoverageAction:
        def __init__(self, runner):
            pass

        def run(self):
            calls.append("ran")
            return 0

    monkeypatch.setattr("cli.manage.CoverageAction", FakeCoverageAction)
    manager = Manager(runner=FakeSubprocessRunner())

    # Act
    exit_code = manager.dispatch(["coverage"])

    # Assert
    assert exit_code == 0
    assert calls == ["ran"]


def test_dispatch_missing_command_errors(capsys) -> None:
    # Arrange
    manager = Manager(runner=FakeSubprocessRunner())

    # Act / Assert — argparse exits with SystemExit(2) when a required
    # subparser is missing.
    with pytest.raises(SystemExit) as exc_info:
        manager.dispatch([])
    assert exc_info.value.code == 2


def test_dispatch_unknown_command_errors(capsys) -> None:
    # Arrange
    manager = Manager(runner=FakeSubprocessRunner())

    # Act / Assert
    with pytest.raises(SystemExit) as exc_info:
        manager.dispatch(["bogus-command"])
    assert exc_info.value.code == 2


def test_dispatch_test_rejects_invalid_suite_name(capsys) -> None:
    # Arrange
    manager = Manager(runner=FakeSubprocessRunner())

    # Act / Assert — argparse `choices` rejects a suite not in SUITES.
    with pytest.raises(SystemExit) as exc_info:
        manager.dispatch(["test", "not-a-real-suite"])
    assert exc_info.value.code == 2


def test_manager_defaults_to_real_subprocess_runner() -> None:
    # Arrange / Act
    from common.subprocess_runner import RealSubprocessRunner

    manager = Manager()

    # Assert
    assert isinstance(manager._runner, RealSubprocessRunner)
