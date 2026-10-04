<!-- Draft for an Audiveris pull request: the last measure of a system is dropped when nothing is recognised in it. From vilpter/audiveris fix/last-measure (0b9de9f97). -->

**Title:** Keep the last measure of a system when a bar line closes it

**Bug**
When nothing is recognised in the last measure of a system, that measure is missing from the export, and so is its right bar line. On the last system, this loses the final measure of the piece and the final bar line style. On scans it happens easily: dark print can fill whole notes in, and then nothing is left in the measure. The MEASURES step counts the measure (`16 raw measures: [8 in system#1, 8 in system#2]`), and nothing in the log says it was dropped.

**Cause**
- `StackRhythm.readStackActualDuration` gives a stack with no voice an actual duration of zero.
- `MeasureFixer.process` takes every empty stack at the end of a system as a cautionary stack, whatever closes it. A cautionary stack holds the courtesy clef, key or time signatures past the last bar line. The empty stack gets the id of the previous stack, with a "C".
- `PartwiseBuilder.processPart` skips cautionary stacks, and `Page.computeMeasureCount` leaves them out. So the measure and its right bar line are not exported.

The stack past the last bar line is the measure that `MeasuresBuilder.buildPartMeasures` adds when the staff goes on after its last bar line, and it has no right bar line. A stack closed by a bar line is a measure.

**Fix**
- An empty stack is cautionary only when it is the last one of its system and has no right bar line. That check is a new private method, `MeasureFixer.isCautionary`.
- Otherwise the stack gets the next id and is exported as an empty measure, as an empty stack inside a system already is.
- Courtesy signatures past the last bar line are handled as before.

This is the counterpart, at the end of a system, of e83741b99 (an empty first measure after the header is kept).

**To reproduce**
[last-measure-scan.png](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/last-measure-scan.png) is a simulated dark photocopy of a generated string trio page, made from [last-measure.ly](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/last-measure.ly) (AGPL) with [degrade.py](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/degrade.py): `degrade.py last-measure.png last-measure-scan.png --thicken 1 --spread 215 --ragged 0.3 --seed 2`. Its last measure holds a whole note in each staff, before the final bar line:

```
Audiveris -batch -export -output out -- last-measure-scan.png
```

**Tested** on Windows (JDK 25), comparing `development` at 214648084 with this branch.

| Input | `development` | This branch |
|---|---|---|
| `last-measure-scan.png` (16 measures) | 15 measures per part; measure 16 and the final bar line are missing | 16 measures; the final bar line is light-heavy. Measure 16 is empty: its whole notes are still not recognised |
| Other degradations of the same page | measure 8 (end of system 1) missing, or both 8 and 16 missing | 16 measures |
| `last-measure.pdf`, the clean engraving | 16 measures | identical export |
| A page with a courtesy key at the end of system 1 and a courtesy time signature at the end of system 2, as PDF, PNG and simulated scan | both cautionary stacks left out, 12 measures | identical exports |

- **Other inputs:** 38 of 156 inputs from the evaluation corpus of [Lilyscan](https://github.com/vilpter/lilyscan) (39 pages, each as PDF, PNG, simulated scan and simulated photo). They are the 18 where `development` exports a measure count different from the raw count or from the ground truth, and 20 others spread across the four kinds.
  - **Exports:** 32 are identical. Three of them export nothing on either build.
  - **The 6 that change:** all are photos or a scan where `development` already reads a stem next to the system's last bar line as a bar line. The empty space between that stem and the bar line was dropped as a cautionary stack. Now it is exported as an empty measure, like the same split inside a system. The 6 exports get one or two more measures (8 in all). The final bar line style, which `development` lost, comes back on 4 of them.
  - **Scores:** against the ground truth, exact measures are unchanged on all 6 (0 to 6%: these photos are misread throughout). Note F1 is unchanged on 5, and goes from 0.20 to 0.18 on one.
  - **Why no other export can change:** in the books `development` saved for all 156 inputs, only those 6 have a cautionary stack.
- `./gradlew test`: 222 tests, 0 failures (1 skipped).
