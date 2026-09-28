"""Tests for cli.actions.validate.ValidateAction."""

from pathlib import Path

from cli.actions.validate import ValidateAction
from tests.helpers.fake_subprocess import FakeSubprocessRunner

# ---------------------------------------------------------------------------
# _check_branch_name
# ---------------------------------------------------------------------------


def test_check_branch_name_valid_feature_branch(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="feature/1-Base_Shell\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_branch_name()

    # Assert
    assert result is True


def test_check_branch_name_valid_bugfix_branch(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="bugfix/42-fix-crash\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_branch_name()

    # Assert
    assert result is True


def test_check_branch_name_invalid_format(tmp_path: Path, capsys) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="main\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_branch_name()

    # Assert
    assert result is False
    assert "Invalid branch name format" in capsys.readouterr().out


def test_check_branch_name_invalid_missing_issue_number(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="feature/no-issue-number\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_branch_name()

    # Assert
    assert result is False


# ---------------------------------------------------------------------------
# _check_commit_messages
# ---------------------------------------------------------------------------


def test_check_commit_messages_valid_issue_format(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="abc123 #1: feat: add login screen\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_commit_messages()

    # Assert
    assert result is True


def test_check_commit_messages_valid_docs_format(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="abc123 docs: update README\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_commit_messages()

    # Assert
    assert result is True


def test_check_commit_messages_invalid_format(tmp_path: Path, capsys) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="abc123 fixed a bug\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_commit_messages()

    # Assert
    assert result is False
    assert "1 invalid commit message" in capsys.readouterr().out


def test_check_commit_messages_no_new_commits(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_commit_messages()

    # Assert
    assert result is True


def test_check_commit_messages_mixed_valid_and_invalid(tmp_path: Path) -> None:
    # Arrange
    runner = FakeSubprocessRunner()
    stdout = "aaa111 #1: feat: valid one\nbbb222 not valid\nccc333 docs: also valid\n"
    runner.add_run(returncode=0, stdout=stdout)
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_commit_messages()

    # Assert
    assert result is False


# ---------------------------------------------------------------------------
# _check_no_todo
# ---------------------------------------------------------------------------


def _make_module_dirs(project_root: Path) -> None:
    # Anglerfish is a single-module app (:app only) — see ValidateAction._MODULE_SRC_DIRS.
    for module in ("app/src",):
        (project_root / module).mkdir(parents=True, exist_ok=True)


def test_check_no_todo_passes_when_clean(tmp_path: Path) -> None:
    # Arrange
    _make_module_dirs(tmp_path)
    (tmp_path / "app" / "src" / "Main.kt").write_text("fun main() {}\n", encoding="utf-8")
    action = ValidateAction(FakeSubprocessRunner(), project_root=tmp_path)

    # Act
    result = action._check_no_todo()

    # Assert
    assert result is True


def test_check_no_todo_detects_todo_comment(tmp_path: Path, capsys) -> None:
    # Arrange
    _make_module_dirs(tmp_path)
    kt_file = tmp_path / "app" / "src" / "Broken.kt"
    kt_file.write_text("fun broken() {\n    // TODO: fix this\n}\n", encoding="utf-8")
    action = ValidateAction(FakeSubprocessRunner(), project_root=tmp_path)

    # Act
    result = action._check_no_todo()

    # Assert
    assert result is False
    assert "TODO" in capsys.readouterr().out


def test_check_no_todo_detects_fixme_comment(tmp_path: Path) -> None:
    # Arrange
    _make_module_dirs(tmp_path)
    kt_file = tmp_path / "app" / "src" / "Broken2.kt"
    kt_file.write_text("// FIXME: broken logic\n", encoding="utf-8")
    action = ValidateAction(FakeSubprocessRunner(), project_root=tmp_path)

    # Act
    result = action._check_no_todo()

    # Assert
    assert result is False


# ---------------------------------------------------------------------------
# _check_large_files
# ---------------------------------------------------------------------------


def test_check_large_files_passes_when_all_small(tmp_path: Path) -> None:
    # Arrange
    small_file = tmp_path / "small.kt"
    small_file.write_text("fun small() {}\n", encoding="utf-8")
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="small.kt\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_large_files()

    # Assert
    assert result is True


def test_check_large_files_flags_oversized_file(tmp_path: Path, capsys) -> None:
    # Arrange
    big_file = tmp_path / "big.kt"
    big_file.write_bytes(b"0" * (500 * 1024 + 1))
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="big.kt\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_large_files()

    # Assert
    assert result is False
    assert "big.kt" in capsys.readouterr().out


def test_check_large_files_exempts_jar_so_aar_extensions(tmp_path: Path) -> None:
    # Arrange — all oversized, but on the exempted extension list.
    exempt_files = ["lib.jar", "native.so", "bundle.aar"]
    for name in exempt_files:
        (tmp_path / name).write_bytes(b"0" * (500 * 1024 + 1))
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="\n".join(exempt_files) + "\n")
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    result = action._check_large_files()

    # Assert
    assert result is True


# ---------------------------------------------------------------------------
# run — full orchestration
# ---------------------------------------------------------------------------


def test_run_returns_zero_when_all_checks_pass(tmp_path: Path) -> None:
    # Arrange
    _make_module_dirs(tmp_path)
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="feature/1-Base_Shell\n")  # branch
    runner.add_run(returncode=0, stdout="\n")  # commits
    runner.add_run(returncode=0, stdout="")  # large files
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 0


def test_run_returns_one_when_any_check_fails(tmp_path: Path) -> None:
    # Arrange
    _make_module_dirs(tmp_path)
    runner = FakeSubprocessRunner()
    runner.add_run(returncode=0, stdout="not-a-valid-branch\n")  # branch — fails
    runner.add_run(returncode=0, stdout="\n")  # commits
    runner.add_run(returncode=0, stdout="")  # large files
    action = ValidateAction(runner, project_root=tmp_path)

    # Act
    exit_code = action.run()

    # Assert
    assert exit_code == 1
