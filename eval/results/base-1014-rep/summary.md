# Evaluation: base-1014-rep

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0, Windows-11-10.0.26200-SP0.

main ac545aa: baseline after #84-#86 (Haydn rebuilt)

| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 36 | 36 | 88.4% | 10.5% | 92.5% | 93.4% | 53.3% | 71.0% | 84.6 | 100.0% | 80.6% | 98.6% | 0.041 |
| category:leadsheet | 4 | 4 | 100.0% | 0.0% | 100.0% | 100.0% | 98.8% | 71.0% | 55.4 | 100.0% | 100.0% | 100.0% | 0.045 |
| category:piano | 4 | 4 | 89.8% | 7.5% | 94.8% | 95.7% | - | - | 85.1 | 100.0% | 25.0% | 99.7% | 0.043 |
| category:quartet | 8 | 8 | 83.9% | 9.1% | 94.6% | 97.1% | - | - | 88.2 | 100.0% | 100.0% | 95.8% | 0.092 |
| category:satb | 12 | 12 | 99.1% | 0.2% | 99.8% | 99.8% | 47.9% | - | 98.4 | 100.0% | 100.0% | 100.0% | 0.033 |
| category:song | 8 | 8 | 71.0% | 36.1% | 74.4% | 75.5% | 35.9% | - | 74.7 | 100.0% | 50.0% | 99.6% | 0.058 |
| variant:pdf | 9 | 9 | 96.5% | 2.9% | 97.4% | 97.7% | 54.8% | 68.4% | 79.8 | 100.0% | 88.9% | 99.9% | 0.039 |
| variant:pdf, gate passed | 9 | 9 | 96.5% | 2.9% | 97.4% | 97.7% | 54.8% | 68.4% | 79.8 | 100.0% | 88.9% | 99.9% | 0.039 |
| variant:photo | 9 | 9 | 81.1% | 15.2% | 88.9% | 90.6% | 57.1% | 63.2% | 55.7 | 100.0% | 77.8% | 96.8% | 0.053 |
| variant:photo, gate passed | 9 | 9 | 81.1% | 15.2% | 88.9% | 90.6% | 57.1% | 63.2% | 55.7 | 100.0% | 77.8% | 96.8% | 0.053 |
| variant:png | 9 | 9 | 87.5% | 15.7% | 89.0% | 89.4% | 43.7% | 89.5% | 64.8 | 100.0% | 77.8% | 99.9% | 0.069 |
| variant:scan | 9 | 9 | 88.7% | 8.4% | 94.5% | 95.8% | 57.5% | 63.2% | 138.2 | 100.0% | 77.8% | 98.0% | 0.075 |
| variant:scan, gate passed | 9 | 9 | 88.7% | 8.4% | 94.5% | 95.8% | 57.5% | 63.2% | 138.2 | 100.0% | 77.8% | 98.0% | 0.075 |

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
| bach-bwv269/photo | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| bach-bwv153-1/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/png | lyric-split | Soprano: split syllables the engine read as one word |
| bach-bwv153-1/scan | part-merge | merged P1 (S.) into P5 (Soprano) |
| bach-bwv153-1/scan | part-merge | merged P2 (A.) into P6 (Alto) |
| bach-bwv153-1/scan | part-merge | merged P3 (T.) into P7 (Tenor) |
| bach-bwv153-1/scan | part-merge | merged P4 (B.) into P8 (Bass) |
| bach-bwv153-1/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/scan | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/scan | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/scan | lyric-split | Soprano: split syllables the engine read as one word |
| bach-bwv153-1/photo | part-merge | merged P1 (S.) into P5 (Soprano) |
| bach-bwv153-1/photo | part-merge | merged P2 (A.) into P6 (Alto) |
| bach-bwv153-1/photo | part-merge | merged P3 (T.) into P7 (Tenor) |
| bach-bwv153-1/photo | part-merge | merged P4 (B.) into P8 (Bass) |
| bach-bwv153-1/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-text | Alto: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-text | Tenor: dropped stray marks read as lyrics |
| bach-bwv153-1/photo | lyric-split | Soprano: split syllables the engine read as one word |
| joplin-maple-leaf/scan | rhythm | Piano: triplet to fill the measure |
| joplin-maple-leaf/photo | rhythm | Piano: triplet to fill the measure |
| haydn-op74-1/pdf | part-merge | merged P1 (Vln. 1) into P4 (Violin 1) |
| haydn-op74-1/pdf | part-merge | merged P2 (Vln. 2) into P5 (Violin 2) |
| haydn-op74-1/pdf | part-merge | merged P3 (Vla.) into P6 (Viola) |
| haydn-op74-1/png | part-merge | merged P1 (Vln. 1) into P5 (Violin 1) |
| haydn-op74-1/png | part-merge | merged P2 (Vln. 2) into P6 (Violin 2) |
| haydn-op74-1/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| haydn-op74-1/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| haydn-op74-1/scan | part-merge | merged P1 (Vln. 1), P2 (Voice) into P6 (Violin 1) |
| haydn-op74-1/scan | part-merge | merged P3 (Vln. 2) into P7 (Violin 2) |
| haydn-op74-1/scan | part-merge | merged P4 (Vla.) into P8 (Viola) |
| haydn-op74-1/scan | part-merge | merged P5 (Vc.) into P9 (Violoncello) |
| haydn-op74-1/photo | part-merge | merged P1 (Voice) into P5 (Violin 1) |
| haydn-op74-1/photo | part-merge | merged P2 (Voice) into P6 (Violin 2) |
| haydn-op74-1/photo | part-merge | merged P3 (Vla.) into P7 (Viola) |
| haydn-op74-1/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| mozart-k80-1/pdf | part-merge | merged P1 (Vln. I) into P4 (Violin I) |
| mozart-k80-1/pdf | part-merge | merged P2 (Vln. II) into P5 (Violin II) |
| mozart-k80-1/pdf | part-merge | merged P3 (Vla.) into P6 (Viola) |
| mozart-k80-1/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| mozart-k80-1/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| mozart-k80-1/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| mozart-k80-1/png | part-merge | merged P4 (Vc.) into P8 (Cello) |
| mozart-k80-1/png | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/scan | part-merge | merged P4 (Vln. I), P1 (Voice) into P8 (Violin I) |
| mozart-k80-1/scan | part-merge | merged P5 (Vln. II), P2 (Voice) into P9 (Violin II) |
| mozart-k80-1/scan | part-merge | merged P3 (Vla.), P6 (Voice) into P10 (Viola) |
| mozart-k80-1/scan | part-merge | merged P7 (Vc.) into P11 (Cello) |
| mozart-k80-1/scan | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/photo | part-merge | merged P1 (Vln. I), P2 (Voice) into P6 (Violin I) |
| mozart-k80-1/photo | part-merge | merged P3 (Voice) into P7 (Violin II) |
| mozart-k80-1/photo | part-merge | merged P4 (Vla.) into P8 (Viola) |
| mozart-k80-1/photo | part-merge | merged P5 (Vc.) into P9 (Cello) |
| mozart-k80-1/photo | rhythm | Viola: undot to fill the measure |
| foster-brown-hair/pdf | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/pdf | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/png | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/png | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/scan | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/scan | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/photo | part-merge | merged P1 (E.Pn) into P2 (Voice) |
| foster-brown-hair/photo | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/pdf | part-merge | merged P1 (Ch) into P3 (Choir) |
| handel-lascia/pdf | part-merge | merged P2 (Pno) into P4 (Piano) |
| handel-lascia/pdf | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/png | part-merge | merged P2 (Ch) into P4 (Choir) |
| handel-lascia/png | lyric-split | Ch: split syllables the engine read as one word |
| handel-lascia/png | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/scan | part-merge | merged P1 (Ch) into P3 (Choir) |
| handel-lascia/scan | part-merge | merged P2 (Pno) into P4 (Piano) |
| handel-lascia/scan | rhythm | Choir: double, triplet to fill the measure |
| handel-lascia/scan | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/photo | part-merge | merged P1 (Ch) into P4 (Choir) |
| handel-lascia/photo | part-merge | merged P2 (Voice) into P6 (Piano) |
| handel-lascia/photo | rhythm | Choir: double, triplet to fill the measure |
| handel-lascia/photo | rhythm | Choir: halve, triplet to fill the measure |
| handel-lascia/photo | lyric-split | Choir: split syllables the engine read as one word |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 175 | 16.6% | 38.7% |
| 0.5-0.7 | 476 | 63.2% | 61.9% |
| 0.7-0.8 | 158 | 80.4% | 75.3% |
| 0.8-0.9 | 771 | 95.3% | 84.2% |
| 0.9-1.0 | 3917 | 99.2% | 97.0% |
