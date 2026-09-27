# Evaluation: m1-baseline

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0, Windows-11-10.0.26200-SP0.

Raw Audiveris 5.11.0 on the generated seed corpus (30 pieces x 4 input variants); no Lilyscan repair stages applied. Compiles/bar-check columns run the pipeline (engine MusicXML -> IR -> LilyPond -> Q1/Q2) on each engine output; Boxes and ECE come from the M3 .omr reader. Dev laptop: Intel i7-8550U (4 cores), 8 GB RAM, two Audiveris processes in parallel plus other work, so s/page is inflated by contention.

| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 120 | 109 | 40.6% | 88.3% | 46.5% | 48.2% | 43.0% | 29.5% | 57.1 | 100.0% | 76.1% | 95.8% | 0.389 |
| category:leadsheet | 16 | 14 | 40.6% | 97.7% | 45.1% | 46.4% | 40.7% | 29.5% | 51.6 | 100.0% | 100.0% | 100.0% | 0.410 |
| category:piano | 24 | 23 | 60.1% | 36.0% | 70.7% | 73.9% | - | - | 44.3 | 100.0% | 34.8% | 99.7% | 0.109 |
| category:quartet | 24 | 23 | 33.2% | 124.3% | 33.7% | 34.6% | - | - | 60.5 | 100.0% | 78.3% | 93.6% | 0.517 |
| category:satb | 32 | 27 | 34.9% | 78.3% | 41.1% | 42.6% | 44.1% | - | 81.1 | 100.0% | 81.5% | 94.0% | 0.421 |
| category:solo | 24 | 22 | 41.1% | 107.7% | 43.4% | 44.9% | - | - | 38.2 | 100.0% | 95.5% | 98.3% | 0.383 |
| variant:pdf | 30 | 29 | 49.9% | 86.3% | 54.9% | 56.7% | 54.3% | 35.2% | 44.6 | 100.0% | 89.7% | 97.6% | 0.391 |
| variant:photo | 30 | 22 | 7.9% | 115.9% | 14.1% | 16.8% | 11.0% | 10.6% | 72.4 | 100.0% | 40.9% | 84.3% | 0.629 |
| variant:png | 30 | 29 | 59.8% | 65.9% | 65.7% | 66.6% | 54.7% | 35.3% | 49.8 | 100.0% | 89.7% | 99.0% | 0.282 |
| variant:scan | 30 | 29 | 44.7% | 85.2% | 51.3% | 52.9% | 51.8% | 36.7% | 61.6 | 100.0% | 75.9% | 97.2% | 0.383 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the engine's confidence (lower is better).

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 129 | 10.8% | 46.0% |
| 0.5-0.7 | 133 | 16.5% | 60.7% |
| 0.7-0.8 | 661 | 52.6% | 77.4% |
| 0.8-0.9 | 755 | 58.7% | 86.9% |
| 0.9-1.0 | 12075 | 54.1% | 94.5% |
