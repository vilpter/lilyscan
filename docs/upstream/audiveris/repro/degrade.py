"""Degrade a clean engraving the way a dark photocopy or scan does: ink spread, ragged
stroke edges with specks, slightly wavy lines. Lilyscan project content, AGPL-3.0-or-later.

Usage: python degrade.py IN.png OUT.png [options]
  --spread  threshold after blurring (0-255): higher gives thicker strokes (default 200)
  --blur    blur radius (Gaussian sigma) before the threshold (default 1.2)
  --ragged  chance that a pixel along a stroke edge turns black (default 0.25)
  --wave    amplitude in pixels of a slow vertical wave across the page (default 1.0)
  --thicken dilate strokes by N pixels before the rest (default 0)
  --seed    random seed (default 1)

repeated-notes-scan.png was made with these two commands (the second on one line):
  lilypond -dno-point-and-click --png -dresolution=300 repeated-notes.ly
  python degrade.py repeated-notes.png repeated-notes-scan.png
      --spread 185 --ragged 0.15 --wave 0.8 --blur 1.0
"""

import argparse

import cv2
import numpy as np

p = argparse.ArgumentParser()
p.add_argument("src")
p.add_argument("dst")
p.add_argument("--spread", type=int, default=200)
p.add_argument("--blur", type=float, default=1.2)
p.add_argument("--ragged", type=float, default=0.25)
p.add_argument("--wave", type=float, default=1.0)
p.add_argument("--thicken", type=int, default=0)
p.add_argument("--seed", type=int, default=1)
a = p.parse_args()
rng = np.random.default_rng(a.seed)
img = cv2.imread(a.src, cv2.IMREAD_GRAYSCALE)
if a.thicken:
    img = cv2.erode(img, np.ones((2 * a.thicken + 1, 2 * a.thicken + 1), np.uint8))
img = img.astype(np.float32)
h, w = img.shape
ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
mapy = (
    ys
    + a.wave * np.sin(2 * np.pi * xs / 700.0)
    + 0.5 * a.wave * np.sin(2 * np.pi * xs / 230.0 + 1.0)
)
img = cv2.remap(img, xs, mapy, cv2.INTER_LINEAR, borderValue=255)
img = cv2.GaussianBlur(img, (0, 0), a.blur)
ink = img < a.spread
edge = cv2.dilate(ink.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & ~ink
ink |= edge & (rng.random(ink.shape) < a.ragged)
inner = ink & ~cv2.erode(ink.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
ink &= ~(inner & (rng.random(ink.shape) < a.ragged / 3))
cv2.imwrite(a.dst, np.where(ink, 0, 255).astype(np.uint8))
