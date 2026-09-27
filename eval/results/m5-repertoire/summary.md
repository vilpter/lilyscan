# Evaluation: m5-repertoire

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0, Windows-11-10.0.26200-SP0.

Real repertoire (D11) with Stage 5 (part-merge, octave-clef, rhythm, lyric-text, lyric-verse, lyric-split, calibrated confidence), on the same cached engine output as m1-repertoire. ECE is in-sample; out-of-fold ECE 0.039 (lilyscan/repair/confidence.json).

| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 36 | 36 | 66.8% | 38.7% | 75.1% | 76.3% | 47.6% | 73.7% | 70.9 | 100.0% | 63.9% | 96.6% | 0.024 |
| category:leadsheet | 4 | 4 | 95.3% | 5.1% | 95.5% | 95.5% | 90.0% | 73.7% | 48.6 | 100.0% | 100.0% | 100.0% | 0.079 |
| category:piano | 4 | 4 | 75.8% | 26.8% | 83.6% | 86.3% | - | - | 69.5 | 100.0% | 25.0% | 99.4% | 0.055 |
| category:quartet | 8 | 8 | 52.6% | 59.0% | 62.4% | 63.3% | - | - | 61.3 | 100.0% | 62.5% | 93.3% | 0.051 |
| category:satb | 12 | 12 | 76.5% | 22.0% | 80.5% | 81.6% | 46.6% | - | 95.4 | 100.0% | 83.3% | 97.6% | 0.042 |
| category:song | 8 | 8 | 57.0% | 51.6% | 65.2% | 66.7% | 27.5% | - | 55.8 | 100.0% | 37.5% | 97.3% | 0.048 |
| variant:pdf | 9 | 9 | 88.4% | 13.5% | 94.2% | 94.4% | 57.5% | 68.4% | 54.9 | 100.0% | 88.9% | 99.6% | 0.056 |
| variant:photo | 9 | 9 | 12.8% | 97.7% | 30.1% | 33.7% | 31.8% | 73.7% | 90.5 | 100.0% | 22.2% | 82.9% | 0.051 |
| variant:png | 9 | 9 | 82.8% | 24.6% | 86.0% | 86.3% | 43.7% | 89.5% | 63.7 | 100.0% | 77.8% | 99.9% | 0.064 |
| variant:scan | 9 | 9 | 83.1% | 19.0% | 90.1% | 90.8% | 57.5% | 63.2% | 74.7 | 100.0% | 66.7% | 97.8% | 0.052 |

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
| bach-bwv269/photo | part-merge | merged P1 (S.) into P4 (Soprano) |
| bach-bwv269/photo | part-merge | merged P2 (A.) into P5 (Alto) |
| bach-bwv269/photo | part-merge | merged P3 (T.) into P6 (Tenor) |
| bach-bwv269/photo | rhythm | Tenor: double to fill the measure |
| bach-bwv269/photo | rhythm | Bass: double to fill the measure |
| bach-bwv269/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| bach-bwv269/photo | lyric-text | Alto: dropped stray marks read as lyrics |
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
| joplin-maple-leaf/scan | rhythm | Piano: triplet to fill the measure |
| mozart-k80-1/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| mozart-k80-1/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| mozart-k80-1/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| mozart-k80-1/pdf | part-merge | merged P4 (Vc.) into P8 (Cello) |
| mozart-k80-1/pdf | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| mozart-k80-1/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| mozart-k80-1/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| mozart-k80-1/png | part-merge | merged P4 (Vc.) into P8 (Cello) |
| mozart-k80-1/png | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| mozart-k80-1/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| mozart-k80-1/scan | part-merge | merged P3 (Vla.) into P7 (Viola) |
| mozart-k80-1/scan | part-merge | merged P4 (Vc.) into P8 (Cello) |
| mozart-k80-1/scan | rhythm | Viola: undot to fill the measure |
| mozart-k80-1/photo | part-merge | merged P3 (Vln. I) into P7 (ViolinI) |
| mozart-k80-1/photo | part-merge | merged P4 (Vln.) into P8 (Violin II) |
| mozart-k80-1/photo | part-merge | merged P5 (Vla.) into P9 (Viola) |
| mozart-k80-1/photo | part-merge | merged P6 (Vc.) into P10 (Cello) |
| foster-brown-hair/pdf | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/pdf | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/png | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/png | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/scan | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/scan | lyric-split | Voice: split syllables the engine read as one word |
| foster-brown-hair/photo | part-merge | merged P1 (E.Pno) into P2 (Voice) |
| foster-brown-hair/photo | lyric-split | Voice: split syllables the engine read as one word |
| handel-lascia/pdf | part-merge | merged P1 (Ch) into P3 (Choir) |
| handel-lascia/pdf | part-merge | merged P2 (Pno) into P4 (Piano) |
| handel-lascia/pdf | rhythm | Choir: double, triplet to fill the measure |
| handel-lascia/pdf | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/png | part-merge | merged P2 (Ch) into P4 (Choir) |
| handel-lascia/png | lyric-split | Ch: split syllables the engine read as one word |
| handel-lascia/png | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/scan | part-merge | merged P1 (Ch) into P3 (Choir) |
| handel-lascia/scan | part-merge | merged P2 (Pno) into P4 (Piano) |
| handel-lascia/scan | rhythm | Choir: double, triplet to fill the measure |
| handel-lascia/scan | lyric-split | Choir: split syllables the engine read as one word |
| handel-lascia/photo | lyric-split | Ch: split syllables the engine read as one word |
| verdi-donna-mobile/photo | lyric-text | Voice: dropped stray marks read as lyrics |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 1011 | 21.9% | 23.6% |
| 0.5-0.7 | 505 | 61.6% | 62.2% |
| 0.7-0.8 | 274 | 75.2% | 75.2% |
| 0.8-0.9 | 567 | 90.5% | 83.7% |
| 0.9-1.0 | 2711 | 99.3% | 97.0% |
