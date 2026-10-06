"""Applies each test's tier marker from its tier directory (tests/<tier>/), so no test file carries one itself."""

from pathlib import Path

import pytest

TIERS = ("unit", "integration_mock", "integration_real", "e2e")
TESTS_ROOT = Path(__file__).parent


def pytest_collection_modifyitems(items):
    for item in items:
        tier = Path(item.path).relative_to(TESTS_ROOT).parts[0]
        if tier in TIERS:
            item.add_marker(getattr(pytest.mark, tier))
