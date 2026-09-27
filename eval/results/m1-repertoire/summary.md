# Evaluation: m1-repertoire

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0, Windows-11-10.0.26200-SP0.

Raw Audiveris 5.11.0 on real repertoire (D11): 9 excerpts of public-domain works from music21's corpus, engraved by Lilyscan's generator, x 4 input variants. Handel and Verdi have Italian lyrics, outside the default OCR languages (eng+lat+deu+fra). Same laptop and contention caveats as m1-baseline.

| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 36 | 36 | 58.8% | 59.9% | 65.5% | 66.6% | 34.9% | 73.7% | 70.9 | 100.0% | 61.1% | 96.6% | 0.170 |
| category:leadsheet | 4 | 4 | 60.9% | 91.5% | 66.2% | 66.2% | 61.9% | 73.7% | 48.6 | 100.0% | 100.0% | 100.0% | 0.248 |
| category:piano | 4 | 4 | 75.8% | 26.6% | 83.7% | 86.3% | - | - | 69.5 | 100.0% | 25.0% | 99.4% | 0.074 |
| category:quartet | 8 | 8 | 43.2% | 91.5% | 50.9% | 51.7% | - | - | 61.3 | 100.0% | 50.0% | 93.3% | 0.298 |
| category:satb | 12 | 12 | 72.2% | 36.0% | 74.8% | 75.5% | 38.2% | - | 95.4 | 100.0% | 83.3% | 97.6% | 0.049 |
| category:song | 8 | 8 | 46.3% | 71.2% | 56.8% | 58.4% | 18.1% | - | 55.8 | 100.0% | 37.5% | 97.3% | 0.274 |
| variant:pdf | 9 | 9 | 79.7% | 34.6% | 83.9% | 84.1% | 42.2% | 68.4% | 54.9 | 100.0% | 88.9% | 99.6% | 0.066 |
| variant:photo | 9 | 9 | 9.6% | 108.2% | 25.1% | 28.1% | 16.7% | 73.7% | 90.5 | 100.0% | 22.2% | 82.9% | 0.527 |
| variant:png | 9 | 9 | 78.5% | 40.2% | 79.9% | 80.2% | 35.8% | 89.5% | 63.7 | 100.0% | 77.8% | 99.9% | 0.108 |
| variant:scan | 9 | 9 | 67.4% | 56.4% | 73.2% | 73.9% | 44.9% | 63.2% | 74.7 | 100.0% | 55.6% | 97.8% | 0.173 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the engine's confidence (lower is better).

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 20 | 10.0% | 43.0% |
| 0.5-0.7 | 160 | 26.9% | 61.2% |
| 0.7-0.8 | 365 | 57.5% | 77.2% |
| 0.8-0.9 | 300 | 68.3% | 86.8% |
| 0.9-1.0 | 3821 | 78.6% | 94.4% |
