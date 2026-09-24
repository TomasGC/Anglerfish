"""Tests for cli.actions.coverage.CoverageAction."""

from pathlib import Path

from cli.actions.coverage import CoverageAction
from tests.helpers.fake_subprocess import FakeSubprocessRunner


class FakeGradleRunner:
    def __init__(self, succeeds: bool = True) -> None:
        self.succeeds = succeeds
        self.tasks_run: list[str] = []

    def run_task(self, task: str, timeout: int = 600, extra_args=None) -> bool:
        self.tasks_run.append(task)
        return self.succeeds


def _write_kover_report(report_path: Path, covered: int, missed: int) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        f"""<?xml version="1.0" ?>
<report name="Intellij Coverage Report">
    <counter type="INSTRUCTION" missed="10" covered="90"/>
    <counter type="LINE" missed="{missed}" covered="{covered}"/>
    <counter type="METHOD" missed="1" covered="9"/>
</report>
""",
        encoding="utf-8",
    )


def _report_path(project_root: Path) -> Path:
    return project_root / "app" / "build" / "reports" / "kover" / "reportDebug.xml"


def test_run_fails_when_gradle_task_fails(tmp_path: Path, capsys) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=False)
    action = CoverageAction(runner=FakeSubprocessRunner(), project_root=tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    assert gradle.tasks_run == ["koverXmlReportDebug"]


def test_run_fails_when_report_missing(tmp_path: Path, capsys) -> None:
    # Arrange
    gradle = FakeGradleRunner(succeeds=True)
    action = CoverageAction(runner=FakeSubprocessRunner(), project_root=tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    assert "coverage report not found" in capsys.readouterr().out


def test_run_passes_when_coverage_above_threshold(tmp_path: Path, capsys) -> None:
    # Arrange
    _write_kover_report(_report_path(tmp_path), covered=90, missed=10)  # 90%
    gradle = FakeGradleRunner(succeeds=True)
    action = CoverageAction(runner=FakeSubprocessRunner(), project_root=tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 0
    assert "90.0%" in capsys.readouterr().out


def test_run_fails_when_coverage_below_threshold(tmp_path: Path, capsys) -> None:
    # Arrange
    _write_kover_report(_report_path(tmp_path), covered=50, missed=50)  # 50%
    gradle = FakeGradleRunner(succeeds=True)
    action = CoverageAction(runner=FakeSubprocessRunner(), project_root=tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
    out = capsys.readouterr().out
    assert "50.0%" in out
    assert "below 80% threshold" in out


def test_run_passes_at_exactly_the_threshold(tmp_path: Path) -> None:
    # Arrange — 80 covered / 20 missed = exactly 80.0%, which must NOT fail
    # (the check is strictly "< threshold").
    _write_kover_report(_report_path(tmp_path), covered=80, missed=20)
    gradle = FakeGradleRunner(succeeds=True)
    action = CoverageAction(runner=FakeSubprocessRunner(), project_root=tmp_path, gradle=gradle)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 0
