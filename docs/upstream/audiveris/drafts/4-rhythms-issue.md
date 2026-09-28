<!-- Draft for Audiveris/audiveris issues. Not posted: needs the owner's OK. -->

**Title:** RHYTHMS fails with a NullPointerException when the parts of a system have different numbers of measures

**Describe the bug**
When the parts of one system end up with different numbers of measures, RHYTHMS throws a `NullPointerException`. The book then ends with "Error in reaching step PAGE", and nothing is exported. This happens when a barline is found on one part's staff but not on the others'. Scans and phone photos of string or choir parts trigger it regularly: in a library of 118 scanned string parts, it lost 2 whole files, including a 7-page score.

```
WARN  Book 2044 | Error processing stub java.lang.NullPointerException: Cannot invoke "org.audiveris.omr.sheet.rhythm.Measure.purgeVoices()" because "measure" is null
	at org.audiveris.omr.sheet.rhythm.Voices.refineSystem(Voices.java:430)
	at org.audiveris.omr.sheet.rhythm.PageRhythm.processRanges(PageRhythm.java:288)
	at org.audiveris.omr.sheet.rhythm.PageRhythm.process(PageRhythm.java:248)
	at org.audiveris.omr.sheet.rhythm.RhythmsStep.doit(RhythmsStep.java:192)
```

The same state also shows up as `Cannot invoke "org.audiveris.omr.sheet.rhythm.Measure.addInter(...)" because ... is null`. It may also explain an `IndexOutOfBoundsException` in `MeasureStack.getXOffset` (via `Slot.computeXOffset`).

**To Reproduce**
A simulated scan of a generated SATB page (public, AGPL):
<https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/satb-01-scan-prepared.png>

```
Audiveris -batch -export -output out -- satb-01-scan-prepared.png
```

It fails on 5.11.0 (build 9e1e55cd) and on `development` at c5381487d.

**What happens**
The saved `.omr` shows the cause. Stack and measure counts per system:

| System | Stacks | Measures in parts 1, 2, 3, 4 |
|---|---|---|
| 0 | 3 | 3, 3, 3, 3 |
| 1 | 3 | **2, 2, 2, 3** |
| 2 | 3 | 3, 3, 3, 3 |

`MeasuresBuilder.buildPartMeasures` builds each part's measures from the barlines of that part's own top staff (`staffBarsMap.get(part.getFirstStaff())`), and adds stacks as the longest part needs them. Nothing makes the parts of a system agree. In system 1, a barline was found on part 4's staff but not on the other three. So the third stack has a measure for part 4 only, and `stack.getMeasureAt(part)` returns null for parts 1 to 3. `Voices.refineSystem` (and other code that walks stacks per part) assumes every part has a measure in every stack.

**Expected behavior**
The book is transcribed, with every part having a measure in every stack.

**Possible directions** (I would like your view before writing a fix):
1. Reconcile barlines across the parts of a system before building measures. Group each part's barline abscissae across parts (within about an interline). A barline found on most parts' staves is added where missing (splitting the measure). A barline found on only a few is dropped (merging measures).
2. After `buildPartMeasures`, give a part that lacks a measure in some stack an empty measure there, placed by abscissa.
3. At minimum, make the consumers skip a missing measure, so one inconsistent system does not cost the whole book. This alone may move the problem to export.

**Audiveris environment:**
- Audiveris 5.11.0 (build 9e1e55cd), Windows 11, installer with its bundled runtime; batch mode.
- `development` at c5381487d, Ubuntu 24.04, Temurin 25.0.4.1, built from source.
