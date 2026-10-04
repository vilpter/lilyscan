<!-- Posted as Audiveris/audiveris#1110 (2026-10-03, owner's OK), from vilpter/audiveris fix/weak-header-clefs (d3c65691e). -->

**Title:** Keep a weak header clef that its staff shows clearly on other systems

**Bug**
Since #998, a staff header clef that grades below `minRegisteredGrade` (0.65) is dropped. Real clefs can grade lower, even on a clean engraved page.

- **satb-01.pdf (below):** the tenor staff's clef is a treble clef with its octave 8, next to the part name "T.". It grades 0.177 on system 2 and 0.300 on system 3, and is dropped on both.
- **satb-07.pdf:** 17 of the 24 staves lose their header clef.

A staff without a clef loses its key signature too (`No effective clef before KeyAlterInter...` in the log). The export then changes the key to C major on those systems, and the clef or key "changes" from one system to the next. Audiveris 5.11.0 kept these clefs.

**Cause**
#998 tells a real clef from one invented on a staff that prints none by its grade alone. On the drum chart of #997, the invented clefs grade 0.055 and 0.074. Real clefs on the pages below grade from 0.04 to 0.64, so no single bar separates the two.

**Fix**
#998 stays as it is. One pass is added after all systems of the sheet are processed (`HeadersStep.doEpilog`), so it does not depend on the order in which systems are processed, nor on their being processed in parallel.

- A staff left without a header clef gets its best candidate, when that candidate is of a kind its staff shows clearly on other systems. Only the best one is considered, so that a clef change is not forced back to the clef of the other systems.
- A staff is matched by its rank among the staves of systems with as many staves.
- `ClefBuilder.findWeakClef` runs the clef lookup again for that staff and registers that candidate.
- A staff that prints no clef shows none clearly anywhere, so it still gets none.

**To reproduce**
[satb-01.pdf](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/satb-01.pdf) and [satb-07.pdf](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/satb-07.pdf) are generated SATB pages engraved by LilyPond (sources next to them, AGPL):

```
Audiveris -batch -export -output out -- satb-01.pdf
```

**Tested** on Windows (JDK 25), comparing `development` at 214648084 with this branch.

| Input | `development` | This branch |
|---|---|---|
| `satb-01.pdf` | no clef on the tenor staff of systems 2 and 3 (staves 7 and 11); key G becomes C from measure 4 | treble clefs kept (0.177, 0.300); key G throughout |
| `satb-07.pdf` | 17 header clefs dropped; 7 key changes the page does not have | 15 kept; 5 key changes left |
| [no-clef.png](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/no-clef.png), staves that print no clef (as in #997) | no clef registered | no clef registered |

- **Other inputs:** 156 inputs: 39 pages of [Lilyscan](https://github.com/vilpter/lilyscan)'s evaluation corpus, each as PDF, PNG, a simulated scan and a simulated photo. The new pass acts only on a staff left without a header clef, and `development` leaves such staves on 37 of the inputs.
  - **Exports:** this branch changes the export of 22 of those 37; the other 15 are identical.
  - **Clef and key changes:** the 37 pages print 2. `development` exports 58, this branch 38; 10 pages lose all of their spurious ones.
  - **Scores:** against the ground truth, notes and measures are equal or better on every one (note F1 on `satb-07.pdf` goes from 0.276 to 0.338).
  - **The other 119 inputs:** none has a staff without a header clef, so the pass does nothing there.

- `./gradlew test`: 222 tests, 0 failures (1 skipped).
