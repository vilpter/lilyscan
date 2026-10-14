<!-- Draft, not posted: needs the owner's OK. Branch vilpter/audiveris fix/part-names (f52f2ca87, three commits on development 10d3c184b; not pushed yet). -->

**Title:** Keep a part together when later systems abbreviate its name

**Bug**
A score names its parts in full on the first system and abbreviates them on the following ones ("Violin I", then "Vln. I"). Audiveris exports each abbreviated system as new parts, so a string trio comes out as 6 parts, each holding some systems' measures and rests elsewhere. OCR variants do the same: "F1." for "Fl.", "V0." for "Vo.", or "13." for "B." each start another part. A one-page flute solo comes out as 2 or 3 parts.

**Cause**
`PartCollation.dispatch` maps a system's part to a logical part only when the name read for it equals the logical part's name or abbreviation, ignoring case. A logical part learns its abbreviation only after such a match (the `resAbbrev` block). So "Vln. I" never matches "Violin I": the first abbreviated system creates new logical parts, and the later systems join those.

Two smaller cases fail the same test:
- A name with no letters, such as "13." read for "B.", matches nothing.
- A logical part whose name was not read on the first system matches no named part, although the `resAbbrev` block is written to learn an abbreviation for it.

**Fix**
- **A new class, `PartNames`, compares two part names.** Each name is reduced to its letters and its instrument number (a last word in digits or roman numerals). Two names match when:
  - the shorter letters abbreviate the longer ones: the same first letter, then a subsequence ("vln" in "violin", "vc" in "violoncello");
  - or they abbreviate one of a few other names of the instrument ("Vc." for "Cello", "Fag." for "Bassoon", "Cb." for "Double Bass");
  - and their numbers agree when both have one ("Vln. II" does not match "Violin I"; "Vln." matches both).
- **OCR variants it accepts:** a digit for a similar letter within a word ("F1.", "V0."), an upper-case I for an l ("FI."), and a lone "l" for the numeral I ("Vln. l"). A name with no letters at all counts as not read.
- **`dispatch` takes the logical part whose name fits best,** not the first whose name matches. An abbreviation can match two instruments: "vln" is a subsequence of "violoncello" as well as "violin", and parts are compared from the bottom of the system up. So a system showing only its violins (the others hidden) would otherwise join them to the cello. The order of preference:
  1. the same name or abbreviation;
  2. the same instrument number;
  3. for abbreviations of three letters or more, the name the abbreviation covers most ("vln" covers half of "violin" and a quarter of "violoncello");
  4. a logical part whose name was not read;
  5. among equals, the first in order, as before. Two letters ("Vc.", or "V0." read for it) are too few to tell instruments apart by cover, so position decides there.
- **Unchanged:** staff counts and line counts must still agree, and parts still cannot swap. A name that fits no logical part still starts a new one.

Unit tests: `PartNamesTest` (7).

**To reproduce**
[part-names.pdf](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/part-names.pdf) is a string trio named in full on its first system and by LilyPond's usual short names after it, made from [part-names.ly](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/part-names.ly) (AGPL):

```
Audiveris -batch -export -output out -- part-names.pdf
```

`development` exports 6 parts: "Vln. I", "Vln. II", "Vla.", "Violin I", "Violin II", "Viola". This branch exports the 3 parts of the page.

**Tested** on Windows (JDK 25), comparing `development` at 10d3c184b with this branch.

| Input | `development` | This branch |
|---|---|---|
| `part-names.pdf` and its 300 dpi PNG | 6 parts | 3 |
| The same trio with empty staves hidden (`\RemoveEmptyStaves`), PDF and PNG | 7 parts | 3 |
| Duets with full names, then short names (violins; flute and English horn), as PDF, PNG and a simulated scan | 2 to 6 parts (2 only on one PNG) | 2 |
| Cello and bass duets, 5 versions | 3 to 6 parts | 2 to 4: misreadings such as "Va." for "Vc." still match nothing |

- **Other inputs:** the 156 public inputs from the evaluation corpus of [Lilyscan](https://github.com/vilpter/lilyscan): 39 pages, each as PDF, PNG, simulated scan and simulated photo, with ground truth. 149 export on both builds.
  - **Parts:** 85 exports have more parts than the page on `development`, 11 on this branch.
    - 7 of the 11 are pages where the piano's brace is not found, so its staves are separate parts with different staff configurations.
    - The other 4 are one test page that printed "Pno" as every staff's short name (a defect of that page, fixed since).
  - **Exports:** 68 identical, 2 differ only in text or layout, 79 changed.
  - **Scores against the ground truth (79 changed exports):**
    - mean exact measures 39% → 67%, and note F1 42% → 77%;
    - 75 better, 2 unchanged in score;
    - 533 measures now read as the ground truth has them, 4 no longer.
  - **The 2 that score lower:**
    - *A simulated photo of a quartet, read as one-staff systems split into several movements.* One movement starts with the cello alone, so "Vln. I" on a later system finds only the cello part, matches it by abbreviation, and joins it. No better candidate exists there, and none of these exports is right.
    - *One clean PNG of a piano piece* has one part on both builds and changes in one measure, a dot. This is not this change's doing. Six builds give one of two exports for that page, each build the same on every run:
      - `development` at 214648084 and at 10d3c184b, and one earlier version of this branch, keep the dot;
      - an unrelated slur fix, and two other versions of this branch, lose it.
- `./gradlew test`: 232 tests, 0 failures (1 skipped).

**Not addressed:** when a part's name fits no logical part in a system, the logical parts beyond it can no longer be matched in that system, so the parts after it start new ones too. On one simulated scan, "P110" read for "Pno" splits off that system's choir as well.
