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


def test_an_outline_through_the_music_is_not_used() -> None:
    # The paper runs off the frame and a shadow darkens its lower left corner: the bright
    # region's outline cuts off the start of the lower staves, so the photo stays whole.
    img = page()
    h, w = img.shape
    ys, xs = np.mgrid[0:h, 0:w]
    img[xs < 0.5 * w * (ys - 0.3 * h) / (0.7 * h)] *= 0.45
    assert find_page(img) is None


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


def scanned_pdf(path: Path, img: np.ndarray) -> None:
    """A PDF whose page is one scanned image (like most PDFs from a library)."""
    pymupdf = pytest.importorskip("pymupdf")
    png = path.with_suffix(".png")
    save_png(img, png)
    doc = pymupdf.open()
    pg = doc.new_page(width=595, height=842)
    pg.insert_image(pg.rect, filename=str(png))
    doc.save(path)


def test_job_pages_become_one_book(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    names = ["00-photo.png", "01-score.pdf", "02-scanned.pdf", "03-broken.pdf"]
    job = store.create(names, {"prepare": True})
    root = job_dir(tmp_path, job.id)
    (root / "input").mkdir(parents=True)
    save_png(photo(page(), seed=3), root / "input" / "00-photo.png")
    born_digital_pdf(root / "input" / "01-score.pdf")
    scanned_pdf(root / "input" / "02-scanned.pdf", scan(page(), seed=5))
    (root / "input" / "03-broken.pdf").write_bytes(b"%PDF-1.4")

    result = tasks.prepare_inputs(job.id)

    pages = result["pages"]
    assert [(p["input"], p.get("kind")) for p in pages] == [
        ("00-photo.png", "image"),
        ("01-score.pdf", "vector"),
        ("02-scanned.pdf", "raster"),
        ("03-broken.pdf", None),
    ]
    assert pages[0]["page_found"] and pages[0]["passed"]
    assert pages[1]["born_digital"] and pages[1]["rendered_dpi"] == 400
    assert pages[2]["page_found"] is False and pages[2]["interline"] is not None
    assert "error" in pages[3] and not pages[3]["passed"]
    ok, book = cv2.imreadmulti(str(root / "prepared" / "pages.tif"))
    assert ok and len(book) == 3
    assert book[1].shape[1] == round(595 * 400 / 72)  # the born-digital page at 400 DPI
    # A page is a scan, so the pages as uploaded are there too for a second engine run.
    ok, as_uploaded = cv2.imreadmulti(str(root / "prepared" / "uploaded.tif"))
    assert ok and len(as_uploaded) == 3
    assert (root / "prepared" / "report.json").is_file()
    store.close()


def test_born_digital_pdf_alone_gets_no_second_run(tmp_path: Path) -> None:
    from lilyscan.ingest.book import assemble

    born_digital_pdf(tmp_path / "score.pdf")
    book = assemble([tmp_path / "score.pdf"], tmp_path / "out")
    assert book.prepared is not None and book.prepared.is_file()
    assert book.uploaded is None and not book.scan_like


def test_job_task_can_skip_preparation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-photo.png"], {"prepare": False})
    (job_dir(tmp_path, job.id) / "input").mkdir(parents=True)
    assert tasks.prepare_inputs(job.id) == {"pages": []}
    store.close()


def page_with_text() -> np.ndarray:
    """The synthetic page with a title and a line of Latin text, as most real pages have."""
    img = (page() * 255).astype(np.uint8)
    for y, text in (
        (70, "Twinkle, Twinkle, Little Star - Variations"),
        (1690, "Allegretto: lightly, with the tip of the bow"),
    ):
        cv2.putText(img, text, (100, y), cv2.FONT_HERSHEY_SIMPLEX, 1.1, 0, 2, cv2.LINE_AA)
    return img.astype(np.float32) / np.float32(255.0)


@pytest.mark.parametrize("turns", [1, 2, 3])
def test_turned_page_comes_back_upright(tmp_path: Path, turns: int) -> None:
    upright = page_with_text()
    src, dst = tmp_path / "turned.png", tmp_path / "prepared.png"
    save_png(np.ascontiguousarray(np.rot90(upright, turns)), src)
    report = prepare_image(src, dst)
    assert report.rotated % 360 == pytest.approx({1: 270, 2: 180, 3: 90}[turns], abs=1)
    out = load_gray(dst)
    assert out.shape == upright.shape
    # The title is at the top again.
    assert out[40:90].mean() < out[1720:1760].mean() + 0.02


def test_the_page_as_uploaded_is_stood_upright_too(tmp_path: Path) -> None:
    # A scan lying on its side: the second engine run gets it upright, otherwise as is.
    from lilyscan.ingest.book import assemble

    upright = page_with_text()
    src = tmp_path / "sideways.png"
    save_png(np.ascontiguousarray(np.rot90(upright, 1)), src)
    book = assemble([src], tmp_path / "prepared")
    assert book.uploaded is not None
    ok, pages = cv2.imreadmulti(str(book.uploaded), flags=cv2.IMREAD_GRAYSCALE)
    assert ok and pages[0].shape == upright.shape
    assert np.abs(pages[0] / 255.0 - upright).mean() < 0.01


@pytest.mark.lilypond
def test_a_dense_choir_page_is_not_turned(tmp_path: Path) -> None:
    # The seed corpus's satb-07: lyrics under every staff and noteheads lined up down
    # the page made a quarter turn look spikier than the staff lines, and the page was
    # turned on its side.
    import json

    from lilyscan.prepare.image import staff_angle
    from lilyscan.synth.corpus import build_corpus, load_spec

    seed = Path(__file__).parents[1] / "eval" / "corpus" / "seed.json"
    piece = next(s for s in load_spec(seed) if s.id == "satb-07")
    spec = tmp_path / "spec.json"
    spec.write_text(json.dumps({"pieces": [piece.to_dict()]}), encoding="utf-8")
    item = build_corpus(spec, tmp_path / "corpus")[0]
    report = prepare_image(item.input("scan"), tmp_path / "scan.png")
    assert abs(report.rotated) < 5
    assert abs(staff_angle(flatten_light(load_gray(item.input("scan"))))) < 5


@pytest.mark.parametrize(
    ("text", "clef", "turned", "expected"),
    [
        # Upright scans the text cue took for upside down (chord slashes and fingerings
        # read as letters), and one with an alto clef the clef cue got wrong.
        (-7.4, 4.7, False, False),
        (14.5, -3.4, False, False),
        # Upright string parts: a bass clef with too little text to count, and handwriting
        # the text cue took for upside down with a clef cue too weak to veto it.
        (0.0, -4.55, False, False),
        (-3.93, 1.46, False, False),
        (-4.63, 0.61, False, False),
        # Both cues lean the other way, or one strongly with nothing against it.
        (-0.8, -1.5, False, True),
        (0.2, -5.2, False, True),
        (-5.1, 0.0, False, True),
        # Too little to turn over a page that came upright...
        (0.0, -1.9, False, False),
        # ...but after a quarter turn, the sign of the evidence decides.
        (0.0, -0.3, True, True),
        (-1.0, 1.5, True, False),
    ],
)
def test_turn_over(text: float, clef: float, turned: bool, expected: bool) -> None:
    from lilyscan.prepare.image import turn_over

    assert turn_over(text, clef, turned) is expected


def test_tilted_photo_is_levelled(tmp_path: Path) -> None:
    from lilyscan.prepare.image import staff_angle

    tilted = cv2.warpAffine(
        page_with_text(),
        cv2.getRotationMatrix2D((620, 877), 12, 1.0),
        (1240, 1754),
        borderValue=1.0,
    )
    assert staff_angle(np.asarray(tilted, dtype=np.float32)) == pytest.approx(-12, abs=0.5)
