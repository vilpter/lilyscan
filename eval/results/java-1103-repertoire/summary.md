# Evaluation: java-1103-repertoire

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0-SNAPSHOT, Linux-6.18.44-fc-v230-amd64-with-java-25.0.5.



| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 36 | 36 | 88.5% | 8.5% | 91.9% | 93.1% | 52.8% | 61.8% | 34.0 | 100.0% | 80.6% | 98.1% | 0.049 |
| category:leadsheet | 4 | 4 | 78.1% | 23.9% | 78.6% | 78.6% | 80.0% | 61.8% | 27.2 | 100.0% | 100.0% | 95.2% | 0.071 |
| category:piano | 4 | 4 | 94.5% | 2.7% | 98.4% | 98.4% | - | - | 37.9 | 100.0% | 25.0% | 100.0% | 0.045 |
| category:quartet | 8 | 8 | 77.3% | 14.0% | 90.7% | 94.4% | - | - | 36.8 | 100.0% | 100.0% | 94.1% | 0.072 |
| category:satb | 12 | 12 | 99.2% | 0.3% | 99.8% | 99.8% | 47.9% | - | 32.1 | 100.0% | 100.0% | 100.0% | 0.039 |
| category:song | 8 | 8 | 83.1% | 16.4% | 84.8% | 86.2% | 44.2% | - | 35.6 | 100.0% | 50.0% | 100.0% | 0.068 |
| variant:pdf | 9 | 9 | 95.9% | 3.4% | 96.8% | 97.2% | 57.0% | 73.7% | 35.4 | 100.0% | 88.9% | 99.9% | 0.041 |
| variant:pdf, gate passed | 9 | 9 | 95.9% | 3.4% | 96.8% | 97.2% | 57.0% | 73.7% | 35.4 | 100.0% | 88.9% | 99.9% | 0.041 |
| variant:photo | 9 | 9 | 80.2% | 14.2% | 90.5% | 92.9% | 57.5% | 68.4% | 24.0 | 100.0% | 77.8% | 95.8% | 0.067 |
| variant:photo, gate passed | 9 | 9 | 80.2% | 14.2% | 90.5% | 92.9% | 57.5% | 68.4% | 24.0 | 100.0% | 77.8% | 95.8% | 0.067 |
| variant:png | 9 | 9 | 95.3% | 3.3% | 96.8% | 97.1% | 55.8% | 84.2% | 21.4 | 100.0% | 77.8% | 99.9% | 0.044 |
| variant:scan | 9 | 9 | 82.6% | 13.2% | 83.6% | 85.1% | 41.0% | 21.1% | 55.4 | 100.0% | 77.8% | 96.7% | 0.064 |
| variant:scan, gate passed | 9 | 9 | 82.6% | 13.2% | 83.6% | 85.1% | 41.0% | 21.1% | 55.4 | 100.0% | 77.8% | 96.7% | 0.064 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the event confidence, the engine's grades or, with repairs, Lilyscan's calibrated confidence (lower is better).

## Repairs

| Item | Rule | Detail |
|---|---|---|
| bach-bwv269/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv269/pdf | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv269/pdf | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv269/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv269/png | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv269/png | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv269/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv269/scan | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv269/scan | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv269/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv269/photo | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | part-merge | merged P1 (S.) into P5 (Soprano) |
| bach-bwv153-1/pdf | part-merge | merged P2 (A.) into P6 (Alto) |
| bach-bwv153-1/pdf | part-merge | merged P3 (T.) into P7 (Tenor) |
| bach-bwv153-1/pdf | part-merge | merged P4 (B.) into P8 (Bass) |
| bach-bwv153-1/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| bach-bwv153-1/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| bach-bwv153-1/png | part-merge | merged P2 (A.) into P6 (Alto) |
| bach-bwv153-1/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| bach-bwv153-1/png | part-merge | merged P4 (B.) into P8 (Bass) |
| bach-bwv153-1/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-split | Soprano: split syllables the engine read as one word |
| bach-bwv153-1/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/scan | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/scan | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | part-merge | merged P1 (S.) into P5 (Soprano) |
| bach-bwv153-1/photo | part-merge | merged P2 (A.) into P6 (Alto) |
| bach-bwv153-1/photo | part-merge | merged P3 (T.) into P7 (Tenor) |
| bach-bwv153-1/photo | part-merge | merged P4 (B.) into P8 (Bass) |
| bach-bwv153-1/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-split | Soprano: split syllables the engine read as one word |
| haydn-op74-1/pdf | lyric-text | Violin 1: dropped stray marks read as lyrics |
| haydn-op74-1/pdf | lyric-text | Violin 2: dropped stray marks read as lyrics |
| haydn-op74-1/pdf | lyric-text | Viola: dropped stray marks read as lyrics |
| haydn-op74-1/scan | lyric-text | Violin 1: dropped stray marks read as lyrics |
| haydn-op74-1/scan | lyric-text | Violin 2: dropped stray marks read as lyrics |
| haydn-op74-1/scan | lyric-text | Viola: dropped stray marks read as lyrics |
| mozart-k80-1/pdf | part-merge | merged P3 (Vln. I), P1 (Vln. I) into P7 (Violin I) |
| mozart-k80-1/pdf | part-merge | merged P2 (Vln. II), P4 (Voice) into P8 (Violin II) |
| mozart-k80-1/pdf | part-merge | merged P5 (Vla.) into P9 (Viola) |
| mozart-k80-1/pdf | part-merge | merged P6 (Vc.) into P10 (Cello) |
| mozart-k80-1/pdf | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| mozart-k80-1/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| mozart-k80-1/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| mozart-k80-1/png | part-merge | merged P4 (Vc.) into P8 (Cello) |
| mozart-k80-1/png | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/scan | part-merge | merged P2 (Vln. 1), P1 (Vln. I) into P6 (Violin I) |
| mozart-k80-1/scan | part-merge | merged P3 (Vln. II) into P7 (Violin II) |
| mozart-k80-1/scan | part-merge | merged P4 (Vla.) into P8 (Viola) |
| mozart-k80-1/scan | part-merge | merged P5 (Vc.) into P9 (Cello) |
| mozart-k80-1/scan | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/photo | part-merge | merged P5 (Vln. I), P1 (Voice) into P6 (Violin I) |
| mozart-k80-1/photo | part-merge | merged P2 (Voice) into P7 (Violin II) |
| mozart-k80-1/photo | part-merge | merged P3 (Voice) into P8 (Viola) |
| mozart-k80-1/photo | part-merge | merged P4 (Vc.) into P9 (Cello) |
| foster-brown-hair/pdf | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/png | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/photo | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/pdf | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/png | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/scan | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/photo | rhythm | Voice: double, triplet to fill the measure |
| handel-lascia/photo | rhythm | Voice: halve, triplet to fill the measure |
| handel-lascia/photo | lyric-split | Voice: split syllables the engine read as one word |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 266 | 37.6% | 38.0% |
| 0.5-0.7 | 389 | 75.3% | 61.6% |
| 0.7-0.8 | 166 | 91.6% | 75.9% |
| 0.8-0.9 | 850 | 95.4% | 84.1% |
| 0.9-1.0 | 3757 | 99.4% | 97.1% |
