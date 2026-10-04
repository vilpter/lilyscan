<!-- Posted as Audiveris/audiveris#1111 (2026-10-03, owner's OK), from vilpter/audiveris fix/overlapping-heads (a8a008df8). -->

**Title:** Keep one head of the same pitch on a stem

**Bug**
On scans and photos, a chord often holds the same note twice. It comes from one head read twice, or from a weak head candidate beside the real one. On the photo below, `development` exports chords such as `A4 A4 E5` and `F4 A4 C5 C5 E5` in a string quartet. In about 120 real scanned string parts read with 5.11.0, 151 of 2,057 chords held the same pitch twice.

**Cause**
`SigReducer.analyzeChords` makes every two heads of the same duration on one stem support each other (`HeadHeadRelation`). `detectOverlaps` excludes overlapping inters only when there is no support between them (`noSupport`). So a second head at the same pitch on the same stem is never excluded, and survives next to the first.

**Fix**
Two heads on one stem, on the same staff and at the same pitch, get an exclusion instead of a support, and the reduction keeps the better one. A unison on one stem is drawn as a single head.

Two heads at one position on either side of the stem, an augmented unison such as F and F sharp, would also lose one head. None occurs in the ground truth of the 156 test pages below. If you would rather keep that case, the exclusion can be limited to heads that overlap. On the photos, though, many of the duplicates sit side by side, as an augmented unison would.

Not addressed here: a weak head one step away from a real one, on the other side of the stem, looks just like a chord second. The two differ only by grade (0.21 against 0.53 on one of the test photos), so it is left alone.

**To reproduce**
[quartet-06-photo.png](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/quartet-06-photo.png) is a simulated phone photo of a generated string quartet page (source `quartet-06.ly` next to it, AGPL):

```
Audiveris -batch -export -output out -- quartet-06-photo.png
```

**Tested** on Windows (JDK 25), comparing `development` at 214648084 with this branch.

| Input | `development` | This branch |
|---|---|---|
| `quartet-06-photo.png` | 4 chords with a pitch twice | none |

- **Other inputs:** 156 inputs: 39 pages of [Lilyscan](https://github.com/vilpter/lilyscan)'s evaluation corpus, each as PDF, PNG, a simulated scan and a simulated photo.
  - **Exports:** 147 are identical.
  - **The 9 that change:** they are exactly the inputs where `development` exports a chord with the same pitch twice: 24 such chords, none left.
  - **Scores:** against the ground truth, notes and measures are equal or better on all 9. On one, exact measures go from 13.9% to 16.7%.

- `./gradlew test`: 222 tests, 0 failures (1 skipped).
