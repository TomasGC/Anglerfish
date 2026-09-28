"""ADB action — connect to an Android device (wireless debugging / mDNS)."""

from typing import Optional

from common.file_utils import get_project_root
from common.subprocess_runner import SubprocessRunner

SUBVERBS = ["connect"]


class AdbAction:
    def __init__(self, runner: SubprocessRunner) -> None:
        self._runner = runner
        self._project_root = get_project_root()

    def _get_connector(self):
        from cli.adb_connect import DeviceConnector

        config = self._project_root / "temp" / ".adb_device_cache.json"
        return DeviceConnector(self._runner, config)

    def run_connect(
        self,
        device: Optional[str] = None,
        pair: Optional[str] = None,
        pair_address: Optional[str] = None,
    ) -> int:
        result = self._get_connector().auto_connect(
            target_device=device,
            pairing_code=pair,
            pairing_address=pair_address,
        )
        return 0 if result else 1
