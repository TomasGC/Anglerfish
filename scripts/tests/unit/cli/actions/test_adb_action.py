#!/usr/bin/env python3
"""Unit tests for AdbAction."""

from cli.actions.adb import SUBVERBS, AdbAction
from tests.helpers.fake_subprocess import FakeSubprocessRunner


class TestSubverbsConstant:
    def test_only_connect_supported(self):
        assert SUBVERBS == ["connect"]


class TestRunConnect:
    def test_returns_zero_on_success(self):
        runner = FakeSubprocessRunner().add_run(stdout="List of devices attached\n192.168.1.10:5555\tdevice\n")
        assert AdbAction(runner).run_connect() == 0

    def test_returns_one_on_failure(self):
        runner = (
            FakeSubprocessRunner().add_run(stdout="List of devices attached\n").add_run(stdout="List of services\n")
        )
        assert AdbAction(runner).run_connect() == 1

    def test_passes_device_arg_through(self):
        runner = (
            FakeSubprocessRunner()
            .add_run(stdout="List of devices attached\n")  # get_connected -> none
            .add_run(
                stdout="List of services\nadb-TARGET123-xyz\t_adb-tls-connect._tcp\t192.168.1.10:39007\n"
            )  # discover
            .add_run(stdout="List of devices attached\n")  # is_device_connected -> not yet
            .add_run(stdout="connected to 192.168.1.10:39007")  # connect
        )
        assert AdbAction(runner).run_connect(device="TARGET123") == 0

    def test_returns_one_when_target_device_not_found(self):
        runner = (
            FakeSubprocessRunner()
            .add_run(stdout="List of devices attached\n")
            .add_run(stdout="List of services\nadb-OTHER999-xyz\t_adb-tls-connect._tcp\t192.168.1.20:40001\n")
        )
        assert AdbAction(runner).run_connect(device="TARGET123") == 1

    def test_passes_pair_args_through(self):
        runner = (
            FakeSubprocessRunner()
            .add_run(stdout="Successfully paired")
            .add_run(stdout="List of services\nadb-ABCD1234EFG-XyZ123\t_adb-tls-connect._tcp\t192.168.1.10:39007\n")
            .add_run(stdout="connected to 192.168.1.10:39007")
        )
        result = AdbAction(runner).run_connect(pair="123456", pair_address="192.168.1.10:45678")
        assert result == 0


class TestLazyCreation:
    def test_get_connector_creates_device_connector(self):
        from cli.adb_connect import DeviceConnector

        runner = FakeSubprocessRunner()
        action = AdbAction(runner)
        connector = action._get_connector()
        assert isinstance(connector, DeviceConnector)
