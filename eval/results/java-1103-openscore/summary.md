# Evaluation: java-1103-openscore

Audiveris 5.11.0, LilyPond 2.26.0, lilyscan 0.1.0-SNAPSHOT, Linux-6.18.44-fc-v230-amd64-with-java-25.0.5.



| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page | Compiles (Q1) | Bar checks (Q2) | Boxes | ECE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 64 | 52 | 71.5% | 27.2% | 74.4% | 75.5% | 65.5% | 0.0% | 31.5 | 100.0% | 96.2% | 97.1% | 0.083 |
| category:quartet | 16 | 16 | 89.6% | 6.7% | 95.5% | 96.1% | - | - | 28.7 | 100.0% | 93.8% | 98.4% | 0.068 |
| category:song | 48 | 36 | 63.4% | 33.5% | 67.3% | 68.6% | 65.5% | 0.0% | 32.4 | 100.0% | 97.2% | 96.5% | 0.092 |
| variant:pdf | 16 | 13 | 74.0% | 25.9% | 75.1% | 76.1% | 67.2% | 0.0% | 33.3 | 100.0% | 100.0% | 97.5% | 0.074 |
| variant:pdf, gate passed | 16 | 13 | 74.0% | 25.9% | 75.1% | 76.1% | 67.2% | 0.0% | 33.3 | 100.0% | 100.0% | 97.5% | 0.074 |
| variant:photo | 16 | 13 | 64.7% | 30.7% | 72.2% | 73.6% | 64.7% | 0.0% | 22.2 | 100.0% | 92.3% | 94.7% | 0.097 |
| variant:photo, gate passed | 16 | 13 | 64.7% | 30.7% | 72.2% | 73.6% | 64.7% | 0.0% | 22.2 | 100.0% | 92.3% | 94.7% | 0.097 |
| variant:png | 16 | 13 | 73.6% | 25.9% | 75.1% | 76.2% | 65.7% | 0.0% | 20.3 | 100.0% | 92.3% | 98.4% | 0.090 |
| variant:scan | 16 | 13 | 73.8% | 26.1% | 74.9% | 76.0% | 64.4% | 0.0% | 50.2 | 100.0% | 100.0% | 97.6% | 0.080 |
| variant:scan, gate passed | 16 | 13 | 73.8% | 26.1% | 74.9% | 76.0% | 64.4% | 0.0% | 50.2 | 100.0% | 100.0% | 97.6% | 0.080 |

Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from the .omr (Stage 3). ECE: expected calibration error of the event confidence, the engine's grades or, with repairs, Lilyscan's calibrated confidence (lower is better).

## Repairs

| Item | Rule | Detail |
|---|---|---|
| schubert-mignon/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| schubert-mignon/pdf | lyric-split | Voice: split syllables the engine read as one word |
| schubert-mignon/png | lyric-text | Voice: dropped stray marks read as lyrics |
| schubert-mignon/png | lyric-split | Voice: split syllables the engine read as one word |
| schubert-mignon/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| schubert-mignon/scan | lyric-split | Voice: split syllables the engine read as one word |
| schubert-mignon/photo | lyric-text | Voice: dropped stray marks read as lyrics |
| schubert-mignon/photo | lyric-split | Voice: split syllables the engine read as one word |
| schumann-c-ich-stand/pdf | lyric-split | Voice: split syllables the engine read as one word |
| schumann-c-ich-stand/png | lyric-split | Voice: split syllables the engine read as one word |
| schumann-c-ich-stand/scan | lyric-split | Voice: split syllables the engine read as one word |
| schumann-c-ich-stand/photo | lyric-split | Voice: split syllables the engine read as one word |
| brahms-die-kraenze/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| brahms-die-kraenze/pdf | lyric-split | Voice: split syllables the engine read as one word |
| brahms-die-kraenze/png | rhythm | Voice: halve to fill the measure |
| brahms-die-kraenze/png | lyric-split | Voice: split syllables the engine read as one word |
| brahms-die-kraenze/scan | lyric-split | Voice: split syllables the engine read as one word |
| brahms-die-kraenze/photo | rhythm | Voice: halve to fill the measure |
| brahms-die-kraenze/photo | lyric-split | Voice: split syllables the engine read as one word |
| hensel-sehnsucht/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| hensel-sehnsucht/pdf | lyric-split | Voice: split syllables the engine read as one word |
| hensel-sehnsucht/png | lyric-text | Voice: dropped stray marks read as lyrics |
| hensel-sehnsucht/png | lyric-split | Voice: split syllables the engine read as one word |
| hensel-sehnsucht/scan | lyric-verse | Voice: verse numbers restarted at 1 in measures 1-4 |
| hensel-sehnsucht/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| hensel-sehnsucht/scan | lyric-split | Voice: split syllables the engine read as one word |
| hensel-sehnsucht/photo | lyric-split | Voice: split syllables the engine read as one word |
| faure-apres-un-reve/pdf | lyric-verse | Voice: verse numbers restarted at 1 in measures 1-3 |
| faure-apres-un-reve/pdf | lyric-split | Voice: split syllables the engine read as one word |
| faure-apres-un-reve/png | lyric-split | Voice: split syllables the engine read as one word |
| faure-apres-un-reve/scan | lyric-verse | Voice: verse numbers restarted at 1 in measures 1-3 |
| faure-apres-un-reve/scan | lyric-split | Voice: split syllables the engine read as one word |
| faure-apres-un-reve/photo | rhythm | Voice: triplet to fill the measure |
| faure-apres-un-reve/photo | lyric-verse | Voice: verse numbers restarted at 1 in measures 1-3 |
| faure-apres-un-reve/photo | lyric-split | Voice: split syllables the engine read as one word |
| chaminade-alleluia/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| chaminade-alleluia/pdf | lyric-split | Voice: split syllables the engine read as one word |
| chaminade-alleluia/png | key-signature | Voice: key signature misread on the system(s) from measure 5; set to the key the piece is read in (0 note(s) respelled) |
| chaminade-alleluia/png | lyric-split | Voice: split syllables the engine read as one word |
| chaminade-alleluia/scan | lyric-split | Voice: split syllables the engine read as one word |
| chaminade-alleluia/photo | lyric-split | Voice: split syllables the engine read as one word |
| elgar-the-torch/pdf | vector-tuplet | Voice: triplets printed in the PDF |
| elgar-the-torch/pdf | vector-tuplet | Voice: triplets printed in the PDF |
| elgar-the-torch/pdf | rhythm | Voice: triplet to fill the measure |
| elgar-the-torch/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/pdf | lyric-split | Voice: split syllables the engine read as one word |
| elgar-the-torch/png | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/png | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/png | lyric-split | Voice: split syllables the engine read as one word |
| elgar-the-torch/scan | rhythm | Voice: triplet to fill the measure |
| elgar-the-torch/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/scan | lyric-verse | Voice: verse numbers restarted at 1 in measures 5-6 |
| elgar-the-torch/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/scan | lyric-split | Voice: split syllables the engine read as one word |
| elgar-the-torch/photo | rhythm | Voice: double to fill the measure |
| elgar-the-torch/photo | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/photo | lyric-verse | Voice: verse numbers restarted at 1 in measures 5-6 |
| elgar-the-torch/photo | lyric-text | Voice: dropped stray marks read as lyrics |
| elgar-the-torch/photo | lyric-split | Voice: split syllables the engine read as one word |
| coleridge-taylor-sea/pdf | rhythm | Voice: dot to fill the measure |
| coleridge-taylor-sea/pdf | lyric-text | Voice: dropped stray marks read as lyrics |
| coleridge-taylor-sea/pdf | lyric-verse | Voice: verse numbers restarted at 1 in measures 4-6 |
| coleridge-taylor-sea/pdf | lyric-text | Piano: dropped stray marks read as lyrics |
| coleridge-taylor-sea/pdf | lyric-split | Voice: split syllables the engine read as one word |
| coleridge-taylor-sea/png | rhythm | Voice: dot to fill the measure |
| coleridge-taylor-sea/png | lyric-text | Voice: dropped stray marks read as lyrics |
| coleridge-taylor-sea/png | lyric-verse | Voice: verse numbers restarted at 1 in measures 4-6 |
| coleridge-taylor-sea/png | lyric-split | Voice: split syllables the engine read as one word |
| coleridge-taylor-sea/scan | rhythm | Voice: dot to fill the measure |
| coleridge-taylor-sea/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| coleridge-taylor-sea/scan | lyric-verse | Voice: verse numbers restarted at 1 in measures 4-6 |
| coleridge-taylor-sea/scan | lyric-text | Voice: dropped stray marks read as lyrics |
| coleridge-taylor-sea/scan | lyric-text | Piano: dropped stray marks read as lyrics |
| coleridge-taylor-sea/scan | lyric-split | Voice: split syllables the engine read as one word |
| coleridge-taylor-sea/photo | rhythm | Voice: dot to fill the measure |
| coleridge-taylor-sea/photo | lyric-text | Voice: dropped stray marks read as lyrics |
| coleridge-taylor-sea/photo | lyric-verse | Voice: verse numbers restarted at 1 in measures 4-6 |
| lehmann-seal-song/pdf | lyric-text | Piano: dropped stray marks read as lyrics |
| lehmann-seal-song/pdf | lyric-split | Voice: split syllables the engine read as one word |
| lehmann-seal-song/png | lyric-text | Piano: dropped stray marks read as lyrics |
| lehmann-seal-song/png | lyric-split | Voice: split syllables the engine read as one word |
| lehmann-seal-song/scan | rhythm | Piano: rests to fill the measure |
| lehmann-seal-song/scan | lyric-text | Piano: dropped stray marks read as lyrics |
| lehmann-seal-song/scan | lyric-split | Voice: split syllables the engine read as one word |
| lehmann-seal-song/photo | lyric-text | Voice: dropped stray marks read as lyrics |
| lehmann-seal-song/photo | lyric-split | Voice: split syllables the engine read as one word |
| haydn-op9-6/scan | key-signature | Viola: key signature misread on the system(s) from measure 5; set to the key the piece is read in (2 note(s) respelled) |
| mozart-k387/photo | rhythm | Violin 2: double to fill the measure |
| mayer-a-major/scan | rhythm | Violin 1: undot to fill the measure |
| mayer-a-major/photo | key-signature | Viola: key signature misread on the system(s) from measure 0; set to the key the piece is read in (2 note(s) respelled) |
| beach-op89/pdf | rhythm | Viola: rests to fill the measure |
| beach-op89/png | rhythm | Viola: rests to fill the measure |
| beach-op89/png | key-signature | Violin 1: key signature misread on the system(s) from measure 5; set to the key the piece is read in (0 note(s) respelled) |
| beach-op89/scan | rhythm | Viola: rests to fill the measure |
| beach-op89/photo | rhythm | Viola: rests to fill the measure |
| beach-op89/photo | rhythm | Violoncello: dot to fill the measure |
| beach-op89/photo | key-signature | Violin 1: key signature misread on the system(s) from measure 5; set to the key the piece is read in (0 note(s) respelled) |

## Confidence calibration (all items)

| Confidence | Events | Accuracy | Mean confidence |
|---|---|---|---|
| 0.0-0.5 | 430 | 40.7% | 35.1% |
| 0.5-0.7 | 851 | 83.0% | 61.6% |
| 0.7-0.8 | 200 | 93.5% | 75.1% |
| 0.8-0.9 | 1630 | 97.7% | 84.8% |
| 0.9-1.0 | 3495 | 99.1% | 96.3% |
