# Evaluation: java-1103-seed

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0-SNAPSHOT, Linux-6.18.44-fc-v230-amd64-with-java-25.0.5.



| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 120 | 116 | 86.4% | 13.2% | 91.8% | 92.4% | 79.0% | 39.3% | 33.9 | 100.0% | 88.8% | 98.4% | 0.017 |
| category:leadsheet | 16 | 16 | 90.1% | 9.1% | 93.6% | 94.2% | 90.6% | 39.3% | 29.6 | 100.0% | 100.0% | 100.0% | 0.019 |
| category:piano | 24 | 24 | 90.3% | 5.6% | 94.6% | 95.4% | - | - | 28.2 | 100.0% | 50.0% | 100.0% | 0.052 |
| category:quartet | 24 | 24 | 87.4% | 12.6% | 92.0% | 92.7% | - | - | 34.7 | 100.0% | 95.8% | 96.8% | 0.034 |
| category:satb | 32 | 28 | 79.1% | 21.5% | 83.6% | 84.3% | 73.2% | - | 46.2 | 100.0% | 100.0% | 97.8% | 0.013 |
| category:solo | 24 | 24 | 96.1% | 1.9% | 98.4% | 98.7% | - | - | 25.1 | 100.0% | 100.0% | 100.0% | 0.025 |
| variant:pdf | 30 | 29 | 93.1% | 8.0% | 95.7% | 95.9% | 80.1% | 41.0% | 34.9 | 100.0% | 96.5% | 99.2% | 0.019 |
| variant:pdf, gate passed | 30 | 29 | 93.1% | 8.0% | 95.7% | 95.9% | 80.1% | 41.0% | 34.9 | 100.0% | 96.5% | 99.2% | 0.019 |
| variant:photo | 30 | 29 | 75.8% | 24.2% | 85.6% | 86.9% | 77.8% | 39.8% | 24.8 | 100.0% | 86.2% | 96.2% | 0.019 |
| variant:photo, gate passed | 30 | 29 | 75.8% | 24.2% | 85.6% | 86.9% | 77.8% | 39.8% | 24.8 | 100.0% | 86.2% | 96.2% | 0.019 |
| variant:png | 30 | 29 | 89.5% | 10.0% | 93.7% | 94.3% | 78.2% | 38.3% | 22.4 | 100.0% | 86.2% | 99.0% | 0.010 |
| variant:scan | 30 | 29 | 87.1% | 10.8% | 92.0% | 92.5% | 79.8% | 38.1% | 53.5 | 100.0% | 86.2% | 99.0% | 0.023 |
| variant:scan, gate passed | 30 | 29 | 87.1% | 10.8% | 92.0% | 92.5% | 79.8% | 38.1% | 53.5 | 100.0% | 86.2% | 99.0% | 0.023 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the event confidence, the engine's grades or, with repairs, Lilyscan's calibrated confidence (lower is better).

## Repairs

| Item | Rule | Detail |
|---|---|---|
| solo-01/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-01/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-01/png | rhythm | Flute: triplet to fill the measure |
| solo-01/scan | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-01/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-02/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-02/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-02/scan | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-02/photo | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-03/pdf | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-03/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-03/scan | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-03/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-03/photo | rhythm | Flute: triplet to fill the measure |
| solo-04/pdf | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-04/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-04/scan | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-04/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| solo-05/pdf | vector-tuplet | F1.: triplets printed in the PDF |
| solo-05/pdf | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-05/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-05/png | rhythm | Flute: triplet to fill the measure |
| solo-05/scan | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-05/scan | rhythm | Flute: triplet to fill the measure |
| solo-05/photo | part-merge | merged P2 (F1.), P1 (Fl.) into P3 (Flute) |
| solo-05/photo | rhythm | Flute: triplet to fill the measure |
| solo-06/pdf | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-06/png | part-merge | merged P1 (F1.) into P2 (Flute) |
| solo-06/scan | part-merge | merged P2 (FI.), P1 (Fl.) into P3 (Flute) |
| solo-06/photo | part-merge | merged P1 (Fl.) into P2 (Flute) |
| piano-02/photo | rhythm | Piano: triplet to fill the measure |
| piano-03/scan | rhythm | Piano: rests to fill the measure |
| piano-04/photo | rhythm | Piano: rests to fill the measure |
| piano-06/photo | rhythm | Piano: triplet to fill the measure |
| satb-01/pdf | vector-tuplet | Tenor: triplets printed in the PDF |
| satb-01/pdf | vector-tuplet | Bass: triplets printed in the PDF |
| satb-01/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/pdf | rhythm | Alto: dot to fill the measure |
| satb-01/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-01/png | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-01/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-01/png | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-01/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/png | rhythm | Soprano: triplet to fill the measure |
| satb-01/png | rhythm | Alto: triplet to fill the measure |
| satb-01/png | rhythm | Bass: triplet to fill the measure |
| satb-01/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/scan | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-01/scan | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-01/scan | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-01/scan | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-01/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/scan | rhythm | Soprano: triplet to fill the measure |
| satb-01/scan | rhythm | Bass: triplet to fill the measure |
| satb-01/scan | lyric-verse | Bass: verse numbers restarted at 1 in measures 1-3 |
| satb-01/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-01/photo | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-01/photo | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-01/photo | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-01/photo | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-01/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 71) |
| satb-01/photo | rhythm | Soprano: triplet to fill the measure |
| satb-01/photo | rhythm | Alto: dot to fill the measure |
| satb-01/photo | rhythm | Tenor: triplet to fill the measure |
| satb-01/photo | rhythm | Bass: triplet to fill the measure |
| satb-01/photo | lyric-verse | Bass: verse numbers restarted at 1 in measures 6-8 |
| satb-01/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-01/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 6-8 |
| satb-02/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-02/png | lyric-verse | Soprano: verse numbers restarted at 1 in measures 6-8 |
| satb-02/png | lyric-verse | Tenor: verse numbers restarted at 1 in measures 6-8 |
| satb-02/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-02/scan | lyric-verse | Soprano: verse numbers restarted at 1 in measures 6-8 |
| satb-02/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-02/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 69) |
| satb-02/photo | rhythm | Soprano: triplet to fill the measure |
| satb-02/photo | rhythm | Tenor: triplet to fill the measure |
| satb-02/photo | rhythm | Bass: triplet to fill the measure |
| satb-02/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-02/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-03/pdf | vector-tuplet | Soprano: triplets printed in the PDF |
| satb-03/pdf | vector-tuplet | Alto: triplets printed in the PDF |
| satb-03/pdf | vector-tuplet | Tenor: triplets printed in the PDF |
| satb-03/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/pdf | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-2 |
| satb-03/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-03/pdf | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/png | rhythm | Soprano: triplet to fill the measure |
| satb-03/png | rhythm | Alto: triplet to fill the measure |
| satb-03/png | rhythm | Tenor: triplet to fill the measure |
| satb-03/png | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-2 |
| satb-03/png | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-03/png | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-03/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-03/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-03/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/scan | rhythm | Soprano: triplet to fill the measure |
| satb-03/scan | rhythm | Alto: triplet to fill the measure |
| satb-03/scan | rhythm | Tenor: triplet to fill the measure |
| satb-03/scan | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-2 |
| satb-03/scan | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-03/scan | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-03/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-03/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 73) |
| satb-03/photo | rhythm | Soprano: triplet to fill the measure |
| satb-03/photo | rhythm | Alto: triplet to fill the measure |
| satb-03/photo | rhythm | Tenor: triplet to fill the measure |
| satb-03/photo | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-2 |
| satb-03/photo | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-03/photo | lyric-text | Bass: dropped page text read as lyrics |
| satb-03/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-04/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-04/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/png | rhythm | Alto: triplet to fill the measure |
| satb-04/png | rhythm | Tenor: triplet to fill the measure |
| satb-04/png | rhythm | Bass: triplet to fill the measure |
| satb-04/png | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-04/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/scan | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-04/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 72) |
| satb-04/photo | rhythm | Soprano: triplet to fill the measure |
| satb-04/photo | rhythm | Alto: triplet to fill the measure |
| satb-04/photo | rhythm | Tenor: triplet to fill the measure |
| satb-04/photo | lyric-verse | Tenor: verse numbers restarted at 1 in measures 1-2 |
| satb-05/pdf | vector-tuplet | Alto: triplets printed in the PDF |
| satb-05/pdf | vector-tuplet | Tenor: triplets printed in the PDF |
| satb-05/pdf | vector-tuplet | Bass: triplets printed in the PDF |
| satb-05/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/pdf | lyric-verse | Soprano: verse numbers restarted at 1 in measures 6-8 |
| satb-05/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/png | rhythm | Soprano: triplet to fill the measure |
| satb-05/png | rhythm | Alto: triplet to fill the measure |
| satb-05/png | rhythm | Tenor: triplet to fill the measure |
| satb-05/png | rhythm | Bass: triplet to fill the measure |
| satb-05/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/scan | rhythm | Soprano: triplet to fill the measure |
| satb-05/scan | rhythm | Tenor: triplet to fill the measure |
| satb-05/scan | rhythm | Bass: triplet to fill the measure |
| satb-05/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-05/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-05/photo | part-merge | merged P1 (S.) into P2 (Soprano) |
| satb-05/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-05/photo | rhythm | Soprano: triplet to fill the measure |
| satb-05/photo | rhythm | Alto: triplet to fill the measure |
| satb-05/photo | rhythm | Tenor: triplet to fill the measure |
| satb-05/photo | rhythm | Bass: triplet to fill the measure |
| satb-05/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-05/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/pdf | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-4 |
| satb-06/pdf | lyric-text | Alto: dropped stray marks read as lyrics |
| satb-06/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-06/png | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-06/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-06/png | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-06/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/png | rhythm | Bass: dot to fill the measure |
| satb-06/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/png | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-4 |
| satb-06/png | lyric-text | Alto: dropped stray marks read as lyrics |
| satb-06/png | lyric-verse | Alto: verse numbers restarted at 1 in measures 5-8 |
| satb-06/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/scan | rhythm | Tenor: undot to fill the measure |
| satb-06/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-06/scan | lyric-text | Tenor: dropped stray marks read as lyrics |
| satb-06/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-06/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 68) |
| satb-06/photo | rhythm | Bass: dot to fill the measure |
| satb-06/photo | lyric-text | Tenor: dropped stray marks read as lyrics |
| satb-06/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-06/photo | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/pdf | vector-tuplet | Soprano: triplets printed in the PDF |
| satb-08/pdf | vector-tuplet | Alto: triplets printed in the PDF |
| satb-08/pdf | vector-tuplet | Tenor: triplets printed in the PDF |
| satb-08/pdf | vector-tuplet | Bass: triplets printed in the PDF |
| satb-08/pdf | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/pdf | rhythm | Soprano: double to fill the measure |
| satb-08/pdf | rhythm | Soprano: triplet to fill the measure |
| satb-08/pdf | rhythm | Alto: triplet to fill the measure |
| satb-08/pdf | rhythm | Tenor: triplet to fill the measure |
| satb-08/pdf | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/pdf | lyric-verse | Soprano: verse numbers restarted at 1 in measures 5-6 |
| satb-08/pdf | lyric-verse | Alto: verse numbers restarted at 1 in measures 1-2 |
| satb-08/pdf | lyric-verse | Tenor: verse numbers restarted at 1 in measures 5-6 |
| satb-08/pdf | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/pdf | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/pdf | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/pdf | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/png | part-merge | merged P1 (S.) into P5 (Soprano) |
| satb-08/png | part-merge | merged P2 (A.) into P6 (Alto) |
| satb-08/png | part-merge | merged P3 (T.) into P7 (Tenor) |
| satb-08/png | part-merge | merged P4 (B.) into P8 (Bass) |
| satb-08/png | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/png | rhythm | Soprano: double to fill the measure |
| satb-08/png | rhythm | Soprano: triplet to fill the measure |
| satb-08/png | rhythm | Alto: triplet to fill the measure |
| satb-08/png | rhythm | Tenor: triplet to fill the measure |
| satb-08/png | rhythm | Bass: triplet to fill the measure |
| satb-08/png | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/png | lyric-verse | Soprano: verse numbers restarted at 1 in measures 5-6 |
| satb-08/png | lyric-verse | Alto: verse numbers restarted at 1 in measures 1-2 |
| satb-08/png | lyric-verse | Tenor: verse numbers restarted at 1 in measures 5-6 |
| satb-08/png | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/png | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/png | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/png | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/scan | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/scan | rhythm | Soprano: triplet to fill the measure |
| satb-08/scan | rhythm | Alto: triplet to fill the measure |
| satb-08/scan | rhythm | Tenor: triplet to fill the measure |
| satb-08/scan | rhythm | Bass: triplet to fill the measure |
| satb-08/scan | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/scan | lyric-verse | Soprano: verse numbers restarted at 1 in measures 5-6 |
| satb-08/scan | lyric-verse | Alto: verse numbers restarted at 1 in measures 1-2 |
| satb-08/scan | lyric-verse | Tenor: verse numbers restarted at 1 in measures 5-6 |
| satb-08/scan | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/scan | lyric-split | Bass: split syllables the engine read as one word |
| satb-08/photo | part-merge | merged P1 (S.), P2 (Voice) into P6 (Soprano) |
| satb-08/photo | part-merge | merged P3 (A.) into P7 (Alto) |
| satb-08/photo | part-merge | merged P4 (T.) into P8 (Tenor) |
| satb-08/photo | part-merge | merged P5 (B.) into P9 (Bass) |
| satb-08/photo | octave-clef | Tenor: treble clef read without its 8; lowered the staff an octave (median note was MIDI 67) |
| satb-08/photo | rhythm | Soprano: triplet to fill the measure |
| satb-08/photo | rhythm | Soprano: halve to fill the measure |
| satb-08/photo | rhythm | Alto: triplet to fill the measure |
| satb-08/photo | rhythm | Tenor: triplet to fill the measure |
| satb-08/photo | rhythm | Bass: triplet to fill the measure |
| satb-08/photo | rhythm | Bass: undot to fill the measure |
| satb-08/photo | lyric-text | Soprano: dropped stray marks read as lyrics |
| satb-08/photo | lyric-verse | Soprano: verse numbers restarted at 1 in measures 1-2, 4-5 |
| satb-08/photo | lyric-verse | Alto: verse numbers restarted at 1 in measures 1-2 |
| satb-08/photo | lyric-verse | Tenor: verse numbers restarted at 1 in measures 4-5 |
| satb-08/photo | lyric-split | Soprano: split syllables the engine read as one word |
| satb-08/photo | lyric-split | Alto: split syllables the engine read as one word |
| satb-08/photo | lyric-split | Tenor: split syllables the engine read as one word |
| satb-08/photo | lyric-split | Bass: split syllables the engine read as one word |
| leadsheet-01/pdf | vector-tuplet | V0.: triplets printed in the PDF |
| leadsheet-01/pdf | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-01/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-01/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-01/png | rhythm | Voice: triplet to fill the measure |
| leadsheet-01/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-01/scan | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-01/scan | rhythm | Voice: triplet to fill the measure |
| leadsheet-01/scan | lyric-verse | Voice: verse numbers restarted at 1 in measures 5-6 |
| leadsheet-01/photo | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-01/photo | rhythm | Voice: triplet to fill the measure |
| leadsheet-01/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/pdf | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-02/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/png | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-02/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/scan | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-02/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-02/photo | part-merge | merged P1 (Vo.) into P2 (Voice) |
| leadsheet-02/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/pdf | vector-tuplet | Vo.: triplets printed in the PDF |
| leadsheet-03/pdf | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-03/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/png | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-03/png | rhythm | Voice: triplet to fill the measure |
| leadsheet-03/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/scan | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-03/scan | rhythm | Voice: triplet to fill the measure |
| leadsheet-03/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-03/photo | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-03/photo | rhythm | Voice: triplet to fill the measure |
| leadsheet-03/photo | lyric-verse | Voice: verse numbers restarted at 1 in measures 1-3 |
| leadsheet-03/photo | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/pdf | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-04/pdf | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-04/pdf | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/png | part-merge | merged P2 (V0.), P1 (Vo.) into P3 (Voice) |
| leadsheet-04/png | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-04/png | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/scan | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-04/scan | lyric-split | Voice: split syllables the engine read as one word |
| leadsheet-04/photo | part-merge | merged P1 (V0.) into P2 (Voice) |
| leadsheet-04/photo | lyric-text | Voice: chord names read as lyrics, moved to chord symbols |
| leadsheet-04/photo | lyric-split | Voice: split syllables the engine read as one word |
| quartet-01/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-01/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-01/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-01/pdf | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/pdf | rhythm | Violin I: triplet to fill the measure |
| quartet-01/pdf | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-01/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-01/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-01/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/png | rhythm | Violin I: triplet to fill the measure |
| quartet-01/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-01/png | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-01/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-01/scan | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-01/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/scan | rhythm | Violin I: triplet to fill the measure |
| quartet-01/scan | rhythm | Violin II: halve, triplet to fill the measure |
| quartet-01/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-01/scan | lyric-text | Violin I: dropped stray marks read as lyrics |
| quartet-01/scan | lyric-text | Viola: dropped stray marks read as lyrics |
| quartet-01/scan | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-01/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-01/photo | part-merge | merged P2 (Voice) into P6 (Violin II) |
| quartet-01/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-01/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-01/photo | lyric-text | Violoncello: dropped page text read as lyrics |
| quartet-02/pdf | vector-tuplet | Viola: triplets printed in the PDF |
| quartet-02/pdf | part-merge | merged P1 (Vln. I) into P4 (Violin I) |
| quartet-02/pdf | part-merge | merged P2 (Vln. II) into P5 (Violin II) |
| quartet-02/pdf | part-merge | merged P3 (Vla.) into P6 (Viola) |
| quartet-02/pdf | rhythm | Violin II: double to fill the measure |
| quartet-02/pdf | rhythm | Viola: triplet to fill the measure |
| quartet-02/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-02/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-02/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-02/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-02/png | rhythm | Violin II: double to fill the measure |
| quartet-02/png | rhythm | Viola: triplet to fill the measure |
| quartet-02/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-02/scan | part-merge | merged P4 (Vln. I), P1 (Vln. I) into P8 (Violin I) |
| quartet-02/scan | part-merge | merged P5 (Vln. II), P2 (Vln. II) into P9 (Violin II) |
| quartet-02/scan | part-merge | merged P3 (Vla.), P6 (Voice) into P10 (Viola) |
| quartet-02/scan | part-merge | merged P7 (Vc.) into P11 (Violoncello) |
| quartet-02/scan | rhythm | Violin II: double to fill the measure |
| quartet-02/scan | rhythm | Viola: triplet to fill the measure |
| quartet-02/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-02/photo | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-02/photo | part-merge | merged P2 (Vln. II) into P6 (Vln. II) |
| quartet-02/photo | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-02/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-02/photo | rhythm | Viola: triplet to fill the measure |
| quartet-02/photo | rhythm | Violoncello: triplet to fill the measure |
| quartet-02/photo | lyric-text | Vln. II: dropped stray marks read as lyrics |
| quartet-03/pdf | part-merge | merged P1 (Vln. I) into P4 (Violin I) |
| quartet-03/pdf | part-merge | merged P2 (Vln. II) into P5 (Violin II) |
| quartet-03/pdf | part-merge | merged P3 (Vla.) into P6 (Viola) |
| quartet-03/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-03/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-03/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-03/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-03/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-03/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-03/scan | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-03/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-03/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-03/photo | part-merge | merged P2 (Voice) into P6 (Violin II) |
| quartet-03/photo | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-03/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-04/pdf | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-04/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/png | rhythm | Violin II: triplet to fill the measure |
| quartet-04/png | rhythm | Viola: triplet to fill the measure |
| quartet-04/png | rhythm | Violoncello: triplet to fill the measure |
| quartet-04/scan | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-04/scan | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-04/scan | part-merge | merged P3 (Voice) into P7 (Viola) |
| quartet-04/scan | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/scan | rhythm | Violoncello: triplet to fill the measure |
| quartet-04/scan | lyric-text | Violin I: dropped stray marks read as lyrics |
| quartet-04/photo | part-merge | merged P1 (Voice) into P5 (Violin I) |
| quartet-04/photo | part-merge | merged P2 (Vln. II) into P6 (Vlolin H) |
| quartet-04/photo | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-04/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-04/photo | rhythm | Viola: triplet to fill the measure |
| quartet-04/photo | rhythm | Violoncello: triplet to fill the measure |
| quartet-04/photo | lyric-text | Violin I: dropped stray marks read as lyrics |
| quartet-05/pdf | vector-tuplet | Violin II: triplets printed in the PDF |
| quartet-05/pdf | part-merge | merged P1 (Vln. I) into P4 (Violin I) |
| quartet-05/pdf | part-merge | merged P2 (Vln. II) into P5 (Violin II) |
| quartet-05/pdf | part-merge | merged P3 (Vla.) into P6 (Viola) |
| quartet-05/png | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-05/png | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-05/png | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-05/png | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-05/png | rhythm | Violin II: triplet to fill the measure |
| quartet-05/png | rhythm | Violin II: dot to fill the measure |
| quartet-05/scan | part-merge | merged P1 (Vln. I) into P4 (Violin I) |
| quartet-05/scan | part-merge | merged P2 (Vln. II) into P5 (Violin II) |
| quartet-05/scan | part-merge | merged P3 (Vla.) into P6 (Viola) |
| quartet-05/scan | rhythm | Violin II: triplet to fill the measure |
| quartet-05/scan | rhythm | Violoncello: undot to fill the measure |
| quartet-05/scan | lyric-text | Violin I: dropped stray marks read as lyrics |
| quartet-05/photo | part-merge | merged P5 (Vla.) into P9 (Viola) |
| quartet-05/photo | part-merge | merged P6 (Vc.) into P10 (Violoncello) |
| quartet-05/photo | part-merge | merged P1 (Vln. I) into P4 (Vln.lI) |
| quartet-05/photo | rhythm | Violin I: double to fill the measure |
| quartet-05/photo | rhythm | Viola: triplet to fill the measure |
| quartet-05/photo | rhythm | Violoncello: triplet to fill the measure |
| quartet-05/photo | lyric-text | Viola: dropped stray marks read as lyrics |
| quartet-06/pdf | part-merge | merged P1 (Vln. I) into P5 (Violin I) |
| quartet-06/pdf | part-merge | merged P2 (Vln. II) into P6 (Violin II) |
| quartet-06/pdf | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-06/pdf | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-06/pdf | rhythm | Viola: triplet to fill the measure |
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
| quartet-06/photo | part-merge | merged P2 (Vln.II) into P6 (Violin II) |
| quartet-06/photo | part-merge | merged P3 (Vla.) into P7 (Viola) |
| quartet-06/photo | part-merge | merged P4 (Vc.) into P8 (Violoncello) |
| quartet-06/photo | lyric-text | Viola: dropped stray marks read as lyrics |
| quartet-06/photo | lyric-text | Violoncello: dropped stray marks read as lyrics |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 259 | 24.3% | 38.6% |
| 0.5-0.7 | 682 | 60.9% | 60.7% |
| 0.7-0.8 | 204 | 62.3% | 75.7% |
| 0.8-0.9 | 1457 | 88.7% | 85.3% |
| 0.9-1.0 | 13867 | 98.2% | 97.1% |
