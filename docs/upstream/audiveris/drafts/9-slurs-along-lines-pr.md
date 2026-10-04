<!-- Draft for an Audiveris pull request (problem set 4 of discussion #1089: staff-line noise read as slurs and ties). -->

**Title:** Discard a slur candidate that runs along a staff line

**Bug**
On scans and photocopies with thick, ragged staff lines, slurs and ties are found along a staff line between notes drawn on that line. Repeated notes on a line come out tied, and the page gets slurs it does not print.

On the page below there is no slur or tie at all, yet `development` exports 7 ties and 18 slurs. On a real scanned violin part, 18 of the 29 slurs Audiveris found lay along a staff line; the page has 8 real slurs.

**Cause**
After line removal, what is left of a thick line between two heads on that line forms short arcs. Their middle lies on the line, and their ends bend towards the heads.

- `ArcRetriever` classifies them as `SHORT` or `STAFF_ARC`. `SlursBuilder.buildSlurs` also tries arcs that are not `SLUR` as seeds, so slur candidates are built from them.
- `purgeStaffLines` only looks at a candidate's end. That end bends towards the head, away from the line, so the candidate is kept.

**Fix**
`SlursBuilder.purgeAlongLines` discards a candidate whose whole central half, from a quarter to three quarters of its points, lies within the thickness of one staff line. The tolerance is the line's measured thickness, capped at `maxStaffLineDy` (0.2 interline), the tolerance `purgeStaffLines` already uses for tangency.

The thickness matters. With 0.2 interline alone, a real tie on a clean PDF was lost, because its middle touches the staff line (2 pixels thick there).

**Known limit**
A real tie whose middle runs onto a staff line looks, once the line is removed, just like the residue of that line. One such tie is in the test set below: between two notes above the staff that dip onto the top line. It is kept on the PDF, where the lines are thin, but lost on the PNG and on a scan of the same page.

**To reproduce**
[repeated-notes-scan.png](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/repeated-notes-scan.png) is a LilyPond page of repeated notes (`repeated-notes.ly`), degraded like a dark photocopy by `degrade.py`. Both are next to it (AGPL), with the exact commands.

```
Audiveris -batch -export -output out -- repeated-notes-scan.png
```

**Tested** on Windows (JDK 25), comparing `development` at 214648084 with this branch.

| Input | `development` | This branch |
|---|---|---|
| `repeated-notes-scan.png` | 7 ties and 18 slurs exported; the page has none | none; the same 108 notes in 33 measures |

- **Other inputs:** 156 inputs: 39 pages of [Lilyscan](https://github.com/vilpter/lilyscan)'s evaluation corpus, each as PDF, PNG, a simulated scan and a simulated photo.
  - **Exports:** 128 are identical.
  - **Slurs:** over all 156, `development` exports 118 slurs, and at most 4 of them are on pages that have slurs; this branch exports 66. 51 of the 52 fewer are on pages that print no slur at all; the last lies flat along a staff line.
  - **Ties:** 4 that the pages do not have are removed. One real tie that `development` misses is found, on a scan. The real tie described under Known limit is lost on 2 inputs.
  - **Measures:** on a piano page, a phantom slur hid a rest: with it gone, one more measure is right on both the PDF and the PNG.
  - **One measure worse elsewhere:** on another piano page, a chord loses two augmentation dots. Nothing there is near a slur. The change only drops three slur-candidate glyphs on another system, which shifts the ids of later inters, and the dots of that chord are then linked to the next heads up (`development` links them correctly). The link of dots to chord heads seems to depend on processing order; that is outside this change.

- `./gradlew test`: 222 tests, 0 failures (1 skipped).
