# Evaluation: m6-prepare

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0, Windows-11-10.0.26200-SP0.

Stage 1 front-end on scans and photos (page detection and perspective, light flattening, staff-line dewarping, photo denoising and sharpening, quality gate), then Audiveris 5.11.0 and Stage 5. Scans are transcribed prepared and as uploaded, keeping the run Lilyscan expects to be better. PDF and PNG use the same cached engine output as m5-repair. Dev laptop, three Audiveris processes in parallel.

| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 120 | 116 | 82.7% | 14.8% | 89.6% | 90.8% | 78.9% | 36.6% | 71.5 | 100.0% | 86.2% | 97.8% | 0.015 |
| category:leadsheet | 16 | 16 | 89.1% | 11.2% | 91.9% | 92.5% | 93.8% | 36.6% | 64.7 | 100.0% | 100.0% | 100.0% | 0.056 |
| category:piano | 24 | 24 | 80.0% | 14.8% | 88.9% | 91.8% | - | - | 56.9 | 100.0% | 41.7% | 99.8% | 0.055 |
| category:quartet | 24 | 24 | 88.7% | 6.1% | 94.7% | 95.4% | - | - | 71.9 | 100.0% | 100.0% | 97.6% | 0.023 |
| category:satb | 32 | 28 | 75.3% | 24.2% | 81.0% | 82.1% | 71.4% | - | 97.9 | 100.0% | 96.4% | 95.9% | 0.029 |
| category:solo | 24 | 24 | 91.1% | 5.8% | 95.3% | 95.7% | - | - | 54.9 | 100.0% | 95.8% | 99.5% | 0.034 |
| variant:pdf | 30 | 29 | 85.3% | 13.9% | 90.7% | 91.8% | 81.4% | 35.2% | 44.6 | 100.0% | 89.7% | 97.6% | 0.015 |
| variant:photo | 30 | 29 | 73.5% | 21.4% | 84.7% | 86.4% | 76.0% | 32.5% | 67.6 | 100.0% | 79.3% | 96.4% | 0.021 |
| variant:photo, gate passed | 30 | 29 | 73.5% | 21.4% | 84.7% | 86.4% | 76.0% | 32.5% | 67.6 | 100.0% | 79.3% | 96.4% | 0.021 |
| variant:png | 30 | 29 | 88.4% | 10.6% | 93.0% | 93.7% | 79.3% | 40.8% | 49.8 | 100.0% | 89.7% | 99.0% | 0.016 |
| variant:scan | 30 | 29 | 83.4% | 13.2% | 90.3% | 91.3% | 78.9% | 38.1% | 123.9 | 100.0% | 86.2% | 98.4% | 0.025 |
| variant:scan, gate passed | 30 | 29 | 83.4% | 13.2% | 90.3% | 91.3% | 78.9% | 38.1% | 123.9 | 100.0% | 86.2% | 98.4% | 0.025 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the event confidence, the engine's grades or, with repairs, Lilyscan's calibrated confidence (lower is better).

## Repairs

| Item | Rule | Detail |
|---|---|---|
| solo-01/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-01/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-01/scan | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-01/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-01/photo | rhythm | Flute: triplet to fill the measure |
| solo-02/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-02/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-02/scan | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-02/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-02/photo | rhythm | Flute: triplet to fill the measure |
| solo-03/pdf | part-merge | merged P2 (Fl.), P1 (F1.) into P3 (Flute) |
| solo-03/pdf | rhythm | Flute: triplet to fill the measure |
| solo-03/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-03/scan | part-merge | merged P2 (Fl.), P1 (F1.) into P3 (Flute) |
| solo-03/scan | rhythm | Flute: triplet to fill the measure |
| solo-03/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-04/pdf | part-merge | merged P2 (Fl.), P1 (F1.) into P3 (Flute) |
| solo-04/pdf | rhythm | Flute: triplet to fill the measure |
| solo-04/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-04/scan | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-04/scan | rhythm | Flute: triplet to fill the measure |
| solo-04/photo | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-04/photo | rhythm | Flute: triplet to fill the measure |
| solo-05/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-05/pdf | rhythm | Flute: triplet to fill the measure |
| solo-05/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-05/scan | part-merge | merged P2 (Fl.), P1 (F1.) into P3 (Flute) |
| solo-05/photo | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-06/pdf | part-merge | merged P2 (Fl.), P1 (F1.) into P3 (Flute) |
| solo-06/pdf | rhythm | Flute: triplet to fill the measure |
| solo-06/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-06/scan | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-06/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-06/photo | rhythm | Flute: triplet to fill the measure |
| piano-01/pdf | rhythm | Piano: triplet to fill the measure |
| piano-02/pdf | rhythm | Piano: triplet to fill the measure |
| piano-02/scan | rhythm | Piano: triplet to fill the measure |
| piano-05/pdf | rhythm | Piano: triplet to fill the measure |
| piano-05/png | rhythm | Piano: triplet to fill the measure |
| piano-06/pdf | rhythm | Piano: triplet to fill the measure |
| piano-06/photo | rhythm | Piano: triplet to fill the measure |
| satb-01/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-01/pdf | rhythm | Alto: triplet to fill the measure |
| satb-01/pdf | rhythm | Tenor: triplet to fill the measure |
| satb-01/pdf | rhythm | Bass: triplet to fill the measure |
| satb-01/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-3 |
| satb-01/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-01/png | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-01/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-01/png | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-01/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/png | rhythm | Alto: triplet to fill the measure |
| satb-01/png | rhythm | Tenor: triplet to fill the measure |
| satb-01/png | rhythm | Bass: triplet to fill the measure |
| satb-01/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/scan | rhythm | Tenor: triplet to fill the measure |
| satb-01/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/photo | rhythm | Alto: triplet to fill the measure |
| satb-01/photo | rhythm | Alto: dot to fill the measure |
| satb-01/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-02/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-02/png | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-02/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-02/png | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-02/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/png | rhythm | Bass: triplet to fill the measure |
| satb-02/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/photo | rhythm | Soprano: triplet to fill the measure |
| satb-02/photo | rhythm | Bass: triplet to fill the measure |
| satb-02/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-03/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-03/pdf | rhythm | Voice: triplet to fill the measure |
| satb-03/pdf | rhythm | Tenor: triplet to fill the measure |
| satb-03/pdf | rhythm | Tenor: dot to fill the measure |
| satb-03/pdf | rhythm | Bass: triplet to fill the measure |
| satb-03/pdf | rhythm | Bass: double to fill the measure |
| satb-03/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 5-6 |
| satb-03/pdf | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/pdf | lyric-split | Voice: split syllables the engine read as one word |
| satb-03/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/png | rhythm | Soprano: triplet to fill the measure |
| satb-03/png | rhythm | Tenor: triplet to fill the measure |
| satb-03/png | rhythm | Bass: triplet to fill the measure |
| satb-03/png | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/scan | rhythm | Soprano: triplet to fill the measure |
| satb-03/scan | rhythm | Tenor: triplet to fill the measure |
| satb-03/scan | rhythm | Bass: triplet to fill the measure |
| satb-03/scan | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/photo | rhythm | Alto: triplet to fill the measure |
| satb-03/photo | rhythm | Tenor: triplet to fill the measure |
| satb-03/photo | rhythm | Bass: triplet to fill the measure |
| satb-03/photo | lyric-text | Bass: dropped page text read as lyrics |
| satb-04/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-04/pdf | rhythm | Bass: triplet to fill the measure |
| satb-04/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/png | rhythm | Tenor: triplet to fill the measure |
| satb-04/png | rhythm | Bass: triplet to fill the measure |
| satb-04/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/scan | rhythm | Alto: triplet to fill the measure |
| satb-04/scan | rhythm | Tenor: triplet to fill the measure |
| satb-04/scan | rhythm | Bass: triplet to fill the measure |
| satb-04/photo | part-merge | merged P5 (S.), P1 (S.) into P8 (Soprano) |
| satb-04/photo | part-merge | merged P6 (A.), P2 (A.) into P9 (Alto) |
| satb-04/photo | part-merge | merged P7 (T.), P3 (T.) into P10 (Tenor) |
| satb-04/photo | part-merge | merged P4 (B.) into P11 (Bass) |
| satb-04/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/photo | rhythm | Alto: triplet to fill the measure |
| satb-04/photo | rhythm | Tenor: triplet to fill the measure |
| satb-04/photo | rhythm | Bass: triplet to fill the measure |
| satb-04/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-04/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/pdf | part-merge | merged P5 (S.), P1 (S.) into P9 (Soprano) |
| satb-05/pdf | part-merge | merged P6 (A.), P2 (A.) into P10 (Alto) |
| satb-05/pdf | part-merge | merged P7 (T.), P3 (T.) into P11 (Tenor) |
| satb-05/pdf | part-merge | merged P8 (B.), P4 (13.) into P12 (Bass) |
| satb-05/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-05/pdf | rhythm | Alto: triplet to fill the measure |
| satb-05/pdf | rhythm | Tenor: triplet to fill the measure |
| satb-05/pdf | rhythm | Bass: triplet to fill the measure |
| satb-05/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/png | rhythm | Alto: triplet to fill the measure |
| satb-05/png | rhythm | Tenor: triplet to fill the measure |
| satb-05/png | rhythm | Bass: triplet to fill the measure |
| satb-05/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/scan | rhythm | Alto: triplet to fill the measure |
| satb-05/scan | rhythm | Tenor: triplet to fill the measure |
| satb-05/scan | rhythm | Bass: triplet to fill the measure |
| satb-05/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/photo | rhythm | Alto: triplet to fill the measure |
| satb-05/photo | rhythm | Tenor: triplet to fill the measure |
| satb-05/photo | rhythm | Bass: triplet to fill the measure |
| satb-05/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/pdf | rhythm | Bass: dot to fill the measure |
| satb-06/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/pdf | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-4 |
| satb-06/pdf | lyric-text | Alto: dropped stray marks read as lyrics |
| satb-06/pdf | lyric-verse | Alto: verse numbers restarted at 1 in measures 5-8 |
| satb-06/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/png | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-4 |
| satb-06/png | lyric-text | Tenor: dropped stray marks read as lyrics |
| satb-06/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/scan | rhythm | Bass: dot to fill the measure |
| satb-06/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/photo | rhythm | Bass: halve to fill the measure |
| satb-06/photo | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-4 |
| satb-06/photo | lyric-text | Alto: dropped stray marks read as lyrics |
| satb-06/photo | lyric-verse | Alto: verse numbers restarted at 1 in measures 5-8 |
| satb-06/photo | lyric-text | Tenor: dropped stray marks read as lyrics |
| satb-06/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-08/pdf | rhythm | Alto: triplet to fill the measure |
| satb-08/pdf | rhythm | Tenor: triplet to fill the measure |
| satb-08/pdf | rhythm | Bass: triplet to fill the measure |
| satb-08/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/png | part-merge | merged P1 (S.) into P3 (Soprano) |
| satb-08/png | part-merge | merged P2 (A.) into P4 (Alto) |
| satb-08/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/png | rhythm | Soprano: triplet to fill the measure |
| satb-08/png | rhythm | Soprano: double to fill the measure |
| satb-08/png | rhythm | Alto: triplet to fill the measure |
| satb-08/png | rhythm | Tenor: triplet to fill the measure |
| satb-08/png | rhythm | Bass: triplet to fill the measure |
| satb-08/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/scan | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-08/scan | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-08/scan | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-08/scan | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-08/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/scan | rhythm | Soprano: triplet to fill the measure |
| satb-08/scan | rhythm | Soprano: double to fill the measure |
| satb-08/scan | rhythm | Soprano: halve to fill the measure |
| satb-08/scan | rhythm | Alto: triplet to fill the measure |
| satb-08/scan | rhythm | Tenor: triplet to fill the measure |
| satb-08/scan | rhythm | Bass: triplet to fill the measure |
| satb-08/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/photo | rhythm | Soprano: triplet to fill the measure |
| satb-08/photo | rhythm | Alto: triplet to fill the measure |
| satb-08/photo | rhythm | Tenor: triplet to fill the measure |
| satb-08/photo | rhythm | Bass: triplet to fill the measure |
| satb-08/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/photo | lyric-split | Bass: split syllables the engine read as one word |
| leadsheet-01/pdf | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-01/pdf | rhythm | Voice: triplet to fill the measure |
| leadsheet-01/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-01/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-01/png | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-01/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-01/scan | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-01/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-01/photo | part-merge | merged P2 (Vo.), P1 (V0.) into P3 (Voice) |
| leadsheet-01/photo | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-01/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/pdf | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-02/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-02/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/scan | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-02/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/photo | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-02/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/pdf | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-03/pdf | rhythm | Voice: triplet to fill the measure |
| leadsheet-03/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-03/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/scan | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-03/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/photo | part-merge | merged P2 (Vo.), P1 (V0.) into P3 (Voice) |
| leadsheet-03/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/pdf | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-04/pdf | rhythm | Voice: triplet to fill the measure |
| leadsheet-04/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-04/png | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-04/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/scan | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-04/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/photo | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-04/photo | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-04/photo | lyric-split | Voice: split syllables the engine read as one word |
| quartet-01/pdf | part-merge | merged P5 (Vln. I), P1 (Vln. I) into P9 (Violin I) |
| quartet-01/pdf | part-merge | merged P6 (Vln. II), P2 (Vln. II) into P10 (Violin II) |
| quartet-01/pdf | part-merge | merged P7 (Vla.), P3 (Vla.) into P11 (Viola) |
| quartet-01/pdf | part-merge | merged P8 (Ve.), P4 (Vc.) into P12 (Violoncello) |
| quartet-01/pdf | rhythm | Violin I: triplet to fill the measure |
| quartet-01/pdf | rhythm | Violin II: triplet to fill the measure |
| quartet-01/pdf | rhythm | Violoncello: triplet to fill the measure |
| quartet-01/pdf | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-01/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-01/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-01/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-01/png | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/scan | part-merge | merged P3 (Vln. I), P1 (Vln. I) into P7 (Violin I) |
| quartet-01/scan | part-merge | merged P4 (Vln. «II), P2 (Vln. II) into P8 (Violin II) |
| quartet-01/scan | part-merge | merged P5 (Vla.) into P9 (Viola) |
| quartet-01/scan | part-merge | merged P6 (Vc.) into P10 (Violoncello) |
| quartet-01/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-01/scan | lyric-text | Viola: dropped stray marks read as lyrics |
| quartet-01/scan | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-01/photo | part-merge | merged P2 (Voice) into P6 (Violin II) |
| quartet-01/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-01/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/photo | rhythm | Violin II: dot to fill the measure |
| quartet-02/pdf | part-merge | merged P5 (Vln. I), P1 (Vln. I) into P9 (Violin I) |
| quartet-02/pdf | part-merge | merged P6 (Vln. II), P2 (Vln. II) into P10 (Violin II) |
| quartet-02/pdf | part-merge | merged P7 (Vla.), P3 (Vla.) into P11 (Viola) |
| quartet-02/pdf | part-merge | merged P8 (Vc.), P4 (Ve.) into P12 (Violoncello) |
| quartet-02/pdf | rhythm | Violin II: triplet to fill the measure |
| quartet-02/pdf | rhythm | Violin II: double to fill the measure |
| quartet-02/pdf | rhythm | Viola: triplet to fill the measure |
| quartet-02/pdf | rhythm | Violoncello: triplet to fill the measure |
| quartet-02/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-02/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-02/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-02/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-02/png | rhythm | Violin II: double to fill the measure |
| quartet-02/png | rhythm | Viola: triplet to fill the measure |
| quartet-02/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-02/scan | part-merge | merged P4 (Voice), P1 (Voice) into P8 (Violin I) |
| quartet-02/scan | part-merge | merged P5 (Vln. II), P2 (Vln. II) into P9 (Violin II) |
| quartet-02/scan | part-merge | merged P3 (Vla.), P6 (Voice) into P10 (Viola) |
| quartet-02/scan | part-merge | merged P7 (Vc.) into P11 (Violoncello) |
| quartet-02/scan | rhythm | Violin II: double to fill the measure |
| quartet-02/scan | rhythm | Viola: triplet to fill the measure |
| quartet-02/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-02/photo | part-merge | merged P2 (Voice) into P6 (Violin II) |
| quartet-02/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-02/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-02/photo | rhythm | Violin II: double to fill the measure |
| quartet-02/photo | rhythm | Viola: triplet to fill the measure |
| quartet-03/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-03/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-03/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-03/pdf | part-merge | merged P4 (VC.) into P8 (Violoncello) |
| quartet-03/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-03/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-03/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-03/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-03/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-03/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-03/scan | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-03/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-03/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-03/photo | part-merge | merged P2 (Voice) into P6 (Violin H) |
| quartet-03/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-03/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-04/pdf | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/pdf | rhythm | Violin II: triplet to fill the measure |
| quartet-04/pdf | rhythm | Viola: triplet to fill the measure |
| quartet-04/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-04/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/png | rhythm | Violin II: triplet to fill the measure |
| quartet-04/png | rhythm | Viola: triplet to fill the measure |
| quartet-04/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/scan | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-04/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/scan | rhythm | Violin II: triplet to fill the measure |
| quartet-04/scan | rhythm | Viola: triplet to fill the measure |
| quartet-04/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-04/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-04/photo | part-merge | merged P2 (Voice) into P6 (Violin II) |
| quartet-04/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-04/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/photo | rhythm | Violin II: triplet to fill the measure |
| quartet-04/photo | rhythm | Viola: triplet to fill the measure |
| quartet-04/photo | rhythm | Violoncello: triplet to fill the measure |
| quartet-05/pdf | part-merge | merged P5 (Vln. I), P1 (Vln. I) into P8 (Violin I) |
| quartet-05/pdf | part-merge | merged P6 (Vln. II), P2 (Vln. II) into P9 (Violin II) |
| quartet-05/pdf | part-merge | merged P7 (Vla.), P3 (Vla.) into P10 (Viola) |
| quartet-05/pdf | part-merge | merged P4 (Vc.) into P11 (Violoncello) |
| quartet-05/pdf | rhythm | Violin II: triplet to fill the measure |
| quartet-05/pdf | rhythm | Violin II: double to fill the measure |
| quartet-05/pdf | rhythm | Violoncello: triplet to fill the measure |
| quartet-05/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-05/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-05/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-05/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-05/png | rhythm | Violin II: triplet to fill the measure |
| quartet-05/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-05/scan | part-merge | merged P1 (Vln. I), P2 (Voice) into P6 (Violin I) |
| quartet-05/scan | part-merge | merged P3 (Vln. II) into P7 (Violin II) |
| quartet-05/scan | part-merge | merged P4 (Vla.) into P8 (Viola) |
| quartet-05/scan | part-merge | merged P5 (Vc.) into P9 (Violoncello) |
| quartet-05/scan | rhythm | Violin II: triplet to fill the measure |
| quartet-05/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-05/scan | lyric-text | Violin I: dropped stray marks read as lyrics |
| quartet-05/photo | part-merge | merged P5 (Vln. I), P1 (Voice) into P8 (Violin I) |
| quartet-05/photo | part-merge | merged P6 (Vln. II), P2 (Vln. II) into P9 (Violin II) |
| quartet-05/photo | part-merge | merged P7 (Vla.), P3 (Vla.) into P10 (Viola) |
| quartet-05/photo | part-merge | merged P4 (Vc.) into P11 (Violoncello) |
| quartet-05/photo | rhythm | Violin II: triplet to fill the measure |
| quartet-05/photo | rhythm | Violoncello: triplet to fill the measure |
| quartet-06/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-06/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-06/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-06/pdf | part-merge | merged P4 (Vo.) into P8 (Violoncello) |
| quartet-06/pdf | rhythm | Viola: triplet to fill the measure |
| quartet-06/pdf | rhythm | Violoncello: triplet to fill the measure |
| quartet-06/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-06/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-06/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-06/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-06/png | rhythm | Viola: triplet to fill the measure |
| quartet-06/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-06/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-06/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-06/scan | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-06/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-06/scan | rhythm | Viola: triplet to fill the measure |
| quartet-06/scan | lyric-text | Violin II: dropped stray marks read as lyrics |
| quartet-06/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-06/photo | part-merge | merged P2 (Voice) into P6 (Violin lI) |
| quartet-06/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-06/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 462 | 36.1% | 39.4% |
| 0.5-0.7 | 902 | 65.2% | 60.1% |
| 0.7-0.8 | 273 | 67.0% | 75.1% |
| 0.8-0.9 | 1696 | 90.6% | 85.2% |
| 0.9-1.0 | 13015 | 97.3% | 96.8% |
