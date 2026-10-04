<!-- Draft for an Audiveris pull request (problem set 1 of discussion #1089). Replaces 4-rhythms-issue.md, which was never posted. -->

**Title:** Do not let a part name on one staff hide the start of its system

**Bug**
RHYTHMS throws a `NullPointerException` and the book exports nothing, when the parts of one system end up with different numbers of measures. The page itself is regular: every staff of the system has the same bar lines.

```
WARN  Book 2063 | Error processing stub java.lang.NullPointerException: Cannot invoke "org.audiveris.omr.sheet.rhythm.Measure.purgeVoices()" because "measure" is null
	at org.audiveris.omr.sheet.rhythm.Voices.refineSystem(Voices.java:430)
	at org.audiveris.omr.sheet.rhythm.PageRhythm.processRanges(PageRhythm.java:288)
	at org.audiveris.omr.sheet.rhythm.PageRhythm.process(PageRhythm.java:248)
	at org.audiveris.omr.sheet.rhythm.RhythmsStep.doit(RhythmsStep.java:192)
```

This is the trace of #970. There the page itself had staves with missing measures, which Audiveris does not support. Here it comes from how the start of a system is found.

**Cause**
In system 2 of the input below, the bass staff's short name "B." is printed on the staff lines, just left of the system's start bar line. The lines are traced through it, so that staff's lines start 39 pixels (about two interlines) left of the start bar line. The lines of the other three staves start at it.

`BarsRetriever.detectStartColumns` checks each staff against `maxLinesLeftToStartBar` (0.15 interline), and one staff out of range is enough to reject the start column for the whole system. Without a start column, each staff keeps its own left end:

1. The stem of the "B" becomes a bar line on the bass staff.
2. `MeasuresBuilder` deletes it as a minority column, and copies the other staves' start bar line into the bass staff.
3. That bar line is no longer at the bass staff's start, so the bass part gets one more measure than the other parts in that system.
4. `Voices.refineSystem` then finds no measure for the other parts in the last stack.

**Fix**
- A column whose lines start before it on most staves is still not the system start.
- On fewer than half of the staves, those lines are taken to be extended by something drawn on them, and the column is the start. Those staves then start there like the others, which drops the stray bar line.
- A one-staff or two-staff system behaves as before: one staff out of two is not fewer than half.
- An INFO line names each staff concerned.

**To reproduce**
[satb-01-scan-prepared.png](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/satb-01-scan-prepared.png) is a simulated scan of a generated SATB page (AGPL):

```
Audiveris -batch -export -output out -- satb-01-scan-prepared.png
```

**Tested** on Windows (JDK 25), comparing `development` at 214648084 with this branch.

| Input | `development` | This branch |
|---|---|---|
| `satb-01-scan-prepared.png` | NPE in RHYTHMS, nothing exported | exported; 8 measures, 3, 2 and 3 per system, as printed |
| The unprepared scan of the same page | exported | the rule applies to one staff of system 3; the music is identical, and only that system's left margin and note positions move (by 7 and 1 or 2 units) |

- **Other inputs:** 156 inputs were run to GRID with this branch: 39 pages of the evaluation corpus of [Lilyscan](https://github.com/vilpter/lilyscan), each as PDF, PNG, a simulated scan and a simulated photo. The rule applies only to that unprepared scan. On the other 155, the code takes the same path as before.

- `./gradlew test`: 222 tests, 0 failures (1 skipped).
