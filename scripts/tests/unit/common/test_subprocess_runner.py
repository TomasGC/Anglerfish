"""Tests for common.subprocess_runner.

RealSubprocessRunner is a thin pass-through to the stdlib `subprocess` module.
This is the one place in the suite where patching the stdlib directly is
appropriate — the whole point of a pass-through is that it forwards to
`subprocess.run`/`subprocess.Popen`, so there's nothing else to fake against.
"""

import subprocess
from unittest.mock import patch

from common.subprocess_runner import RealSubprocessRunner, SubprocessRunner


def test_real_subprocess_runner_satisfies_protocol() -> None:
    # Arrange / Act
    runner = RealSubprocessRunner()

    # Assert
    assert isinstance(runner, SubprocessRunner)


def test_run_delegates_to_subprocess_run() -> None:
    # Arrange
    runner = RealSubprocessRunner()
    expected = subprocess.CompletedProcess(args=["echo", "hi"], returncode=0)

    # Act
    with patch("subprocess.run", return_value=expected) as mock_run:
        result = runner.run(["echo", "hi"], capture_output=True, timeout=5)

    # Assert
    mock_run.assert_called_once_with(["echo", "hi"], capture_output=True, timeout=5)
    assert result is expected


def test_popen_delegates_to_subprocess_popen() -> None:
    # Arrange
    runner = RealSubprocessRunner()
    fake_process = object()

    # Act
    with patch("subprocess.Popen", return_value=fake_process) as mock_popen:
        result = runner.popen(["gradlew.bat", "test"], cwd="/some/dir")

    # Assert
    mock_popen.assert_called_once_with(["gradlew.bat", "test"], cwd="/some/dir")
    assert result is fake_process
