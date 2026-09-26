from __future__ import annotations

import pytest

from lilyscan.runtime.config import DEFAULT_OCR_LANGUAGES, Settings, parse_ocr_languages


def test_default_ocr_languages_cover_lyrics_defaults() -> None:
    assert DEFAULT_OCR_LANGUAGES.split("+") == ["eng", "lat", "deu", "fra"]
    assert Settings.from_env({}).ocr_languages == DEFAULT_OCR_LANGUAGES


@pytest.mark.parametrize(
    ("spec", "expected"),
    [
        ("eng+lat", "eng+lat"),
        (" ENG+Lat ", "eng+lat"),
        ("eng,deu", "eng+deu"),
        ("eng+eng+fra", "eng+fra"),
        ("chi_sim+eng", "chi_sim+eng"),
        ("deu_latf", "deu_latf"),
    ],
)
def test_parse_ocr_languages(spec: str, expected: str) -> None:
    assert parse_ocr_languages(spec) == expected


@pytest.mark.parametrize("spec", ["", "+", "en", "english", "eng+../x", "eng;rm", "eng deu"])
def test_parse_ocr_languages_rejects(spec: str) -> None:
    with pytest.raises(ValueError):
        parse_ocr_languages(spec)


def test_env_override() -> None:
    s = Settings.from_env({"LILYSCAN_OCR_LANGUAGES": "eng+ita"})
    assert s.ocr_languages == "eng+ita"
