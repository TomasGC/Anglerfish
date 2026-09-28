"""Manager — unified entry point for all Anglerfish project operations."""

from typing import Optional

from cli.actions.adb import AdbAction
from cli.actions.build import BuildAction
from cli.actions.coverage import CoverageAction
from cli.actions.test import SUITES as TEST_SUITES
from cli.actions.test import TestAction
from cli.actions.validate import ValidateAction
from common.subprocess_runner import RealSubprocessRunner, SubprocessRunner


class Manager:
    """Dispatches project operations to action classes."""

    def __init__(self, runner: Optional[SubprocessRunner] = None) -> None:
        self._runner = runner or RealSubprocessRunner()

    def dispatch(self, argv: Optional[list] = None) -> int:
        import argparse

        p = argparse.ArgumentParser(prog="manage", description="Anglerfish project manager")
        sub = p.add_subparsers(dest="command", required=True)

        bp = sub.add_parser("build", help="Build debug APK")
        bp.add_argument("--no-install", action="store_true")

        tp = sub.add_parser("test", help="Run the Kotlin test suite")
        tp.add_argument(
            "suites",
            nargs="*",
            choices=TEST_SUITES,
            metavar="SUITE",
            help=f"Suites to run: {', '.join(TEST_SUITES)} (default: all)",
        )

        sub.add_parser(
            "validate",
            help="Validate branch name, commit messages, no-TODO, large files",
        )

        sub.add_parser("coverage", help="Generate Kover XML report and verify 80% threshold")

        ap = sub.add_parser("adb", help="ADB device management")
        adb_sub = ap.add_subparsers(dest="subverb", required=True)

        adb_connect = adb_sub.add_parser("connect", help="Connect to device via mDNS (wireless debugging)")
        adb_connect.add_argument("--device", default=None)
        adb_connect.add_argument("--pair", metavar="CODE", default=None)
        adb_connect.add_argument("--pair-address", metavar="IP:PORT", default=None)

        args = p.parse_args(argv)

        if args.command == "build":
            return BuildAction(self._runner).run(install=not args.no_install)

        if args.command == "test":
            return TestAction(self._runner).run(suites=args.suites)

        if args.command == "validate":
            return ValidateAction(self._runner).run()

        if args.command == "coverage":
            return CoverageAction(self._runner).run()

        if args.command == "adb":
            adb = AdbAction(self._runner)
            if args.subverb == "connect":
                return adb.run_connect(
                    device=args.device,
                    pair=args.pair,
                    pair_address=args.pair_address,
                )

        return 1  # pragma: no cover
