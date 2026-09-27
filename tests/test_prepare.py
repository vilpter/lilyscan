"""Stage 1: page detection, light flattening, scale, dewarping, quality gate."""

from __future__ import annotations

from pathlib import Path

import pytest

np = pytest.importorskip("numpy")
cv2 = pytest.importorskip("cv2")

from lilyscan.prepare import prepare_image  # noqa: E402
from lilyscan.prepare.image import (  # noqa: E402
    find_page,
    flatten_light,
    ink_mask,
    load_gray,
    save_png,
)
from lilyscan.prepare.staff import curvature, displacement_samples, measure_scale  # noqa: E402
from lilyscan.synth.degrade import photo, scan  # noqa: E402

INTERLINE = 20
THICKNESS = 3


def page(width: int = 1240, height: int = 1754, systems: int = 10) -> np.ndarray:
    """A white page with five-line staves and some noteheads and stems."""
    img = np.ones((height, width), dtype=np.float32)
    rng = np.random.default_rng(1)
    top = 120
    for s in range(systems):
        y0 = top + s * 150
        for line in range(5):
            y = y0 + line * INTERLINE
            img[y : y + THICKNESS, 80 : width - 80] = 0.0
        for x in range(140, width - 120, 70):
            cy = y0 + int(rng.integers(0, 9)) * INTERLINE // 2
            cv2.ellipse(img, (x, cy), (12, 9), -20, 0, 360, 0.0, -1)
            cv2.line(img, (x + 11, cy), (x + 11, cy - 70), 0.0, 2)
    return img


def test_scale_of_a_clean_page() -> None:
    scale = measure_scale(ink_mask(page()))
    assert scale is not None
    assert abs(scale.thickness - THICKNESS) <= 1
    assert abs(scale.interline - INTERLINE) <= 1


def test_a_clean_page_fills_the_frame() -> None:
    assert find_page(page()) is None


def test_light_is_flattened() -> None:
    img = page()
    h, w = img.shape
    ramp = np.linspace(0.55, 1.0, w, dtype=np.float32)[None, :].repeat(h, axis=0)
    flat = flatten_light(img * ramp)
    paper = flat[img > 0.5]
    assert paper.mean() > 0.95 and paper.std() < 0.05


def test_photo_is_found_straightened_and_passes_the_gate(tmp_path: Path) -> None:
    src, dst = tmp_path / "photo.png", tmp_path / "prepared.png"
    save_png(photo(page(), seed=3), src)
    report = prepare_image(src, dst)
    assert report.page_found
    assert report.interline is not None and abs(report.interline - INTERLINE) < 2.5
    assert report.dewarped and report.curvature_before is not None
    assert (
        report.curvature_after is not None
        and report.curvature_after < 0.5 * report.curvature_before
    )
    assert report.passed, report.warnings
    assert report.cleaned == "sharpen"  # photos are denoised and sharpened
    out = load_gray(dst)
    lines = curvature(displacement_samples(ink_mask(out), report.interline)) / report.interline
    assert lines < 0.15


def test_scan_is_deskewed_without_cropping(tmp_path: Path) -> None:
    src, dst = tmp_path / "scan.png", tmp_path / "prepared.png"
    save_png(scan(page(), seed=5), src)
    report = prepare_image(src, dst)
    assert not report.page_found and report.cleaned is None
    assert report.output_size == report.input_size
    assert report.curvature_after is not None and report.curvature_after < 0.15


def test_blank_page_warns(tmp_path: Path) -> None:
    src, dst = tmp_path / "blank.png", tmp_path / "prepared.png"
    save_png(np.ones((800, 600), dtype=np.float32), src)
    report = prepare_image(src, dst)
    assert not report.passed and report.warnings == ["no staff lines found"]
    assert dst.is_file()


def born_digital_pdf(path: Path) -> None:
    """A one-page PDF drawn with vector staff lines and text, like an engraver's output."""
    pymupdf = pytest.importorskip("pymupdf")
    doc = pymupdf.open()
    pg = doc.new_page(width=595, height=842)
    for staff in range(4):
        for line in range(5):
            y = 100 + staff * 80 + line * 5
            pg.draw_line((60, y), (535, y), width=0.5)
    pg.insert_text((250, 60), "Title", fontsize=16)
    doc.save(path)


def test_job_task_prepares_photos_and_pdfs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    names = ["00-photo.png", "01-score.pdf", "02-broken.pdf", "03-scan.tif"]
    job = store.create(names, {"prepare": True})
    root = job_dir(tmp_path, job.id)
    (root / "input").mkdir(parents=True)
    save_png(photo(page(), seed=3), root / "input" / "00-photo.png")
    born_digital_pdf(root / "input" / "01-score.pdf")
    (root / "input" / "02-broken.pdf").write_bytes(b"%PDF-1.4")
    (root / "input" / "03-scan.tif").write_bytes(b"II*")

    result = tasks.prepare_inputs(job.id)

    pages = {p["input"]: p for p in result["pages"]}
    assert set(pages) == {"00-photo.png", "01-score.pdf", "02-broken.pdf"}  # TIFFs pass
    assert pages["00-photo.png"]["page_found"] and pages["00-photo.png"]["passed"]
    assert pages["01-score.pdf"]["born_digital"] and pages["01-score.pdf"]["rendered_dpi"] == 400
    assert "error" in pages["02-broken.pdf"] and not pages["02-broken.pdf"]["passed"]
    assert tasks._prepared(root, "00-photo.png").name == "00-photo.png"
    assert tasks._prepared(root, "01-score.pdf").name == "01-score.tif"
    ok, rendered = cv2.imreadmulti(str(root / "prepared" / "01-score.tif"))
    assert ok and len(rendered) == 1 and rendered[0].shape[1] == round(595 * 400 / 72)
    assert (root / "prepared" / "report.json").is_file()
    store.close()


def test_job_task_can_skip_preparation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-photo.png"], {"prepare": False})
    (job_dir(tmp_path, job.id) / "input").mkdir(parents=True)
    assert tasks.prepare_inputs(job.id) == {"pages": []}
    store.close()
