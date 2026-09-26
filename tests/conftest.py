from __future__ import annotations

import shutil

import pytest

from lilyscan.runtime.config import Settings


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    settings = Settings.from_env()
    skip_ly = pytest.mark.skip(reason=f"LilyPond not found ({settings.lilypond_bin})")
    skip_engine = pytest.mark.skip(reason=f"Audiveris not found ({settings.audiveris_bin})")
    has_ly = shutil.which(settings.lilypond_bin) is not None
    has_engine = shutil.which(settings.audiveris_bin) is not None
    for item in items:
        if "lilypond" in item.keywords and not has_ly:
            item.add_marker(skip_ly)
        if "engine" in item.keywords and not has_engine:
            item.add_marker(skip_engine)
        if "gpu" in item.keywords:
            from lilyscan.runtime.device import Device, get_device

            if get_device().device is not Device.CUDA:
                item.add_marker(pytest.mark.skip(reason="no CUDA device"))
