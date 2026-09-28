<!-- Draft for Audiveris/audiveris issues. Not posted: needs the owner's OK. -->

**Title:** CURVES fails with a NullPointerException when a curve starts in a page margin (`ArcRetriever.isStaffArc`)

**Describe the bug**
On a page with instrument names printed to the left of the systems, the CURVES step fails with a `NullPointerException` in `ArcRetriever.isStaffArc`. The book then ends with "Error in reaching step PAGE", and nothing is exported.

```
WARN  Book 2044 | Error processing stub java.lang.NullPointerException: Cannot invoke "org.audiveris.omr.sheet.Staff.isTablature()" because "staff" is null
Caused by: java.lang.NullPointerException: Cannot invoke "org.audiveris.omr.sheet.Staff.isTablature()" because "staff" is null
	at org.audiveris.omr.sheet.curve.ArcRetriever.isStaffArc(ArcRetriever.java:286)
	at org.audiveris.omr.sheet.curve.ArcRetriever.determineShape(ArcRetriever.java:193)
	at org.audiveris.omr.sheet.curve.ArcRetriever.scanArc(ArcRetriever.java:424)
	at org.audiveris.omr.sheet.curve.ArcRetriever.scanImage(ArcRetriever.java:459)
	at org.audiveris.omr.sheet.curve.Curves.buildCurves(Curves.java:136)
	at org.audiveris.omr.sheet.curve.CurvesStep.doit(CurvesStep.java:54)
```

**To Reproduce**
A one-page SATB score engraved by LilyPond 2.26 from generated music (public, AGPL):
<https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/satb-07.pdf>

```
Audiveris -batch -export -output out -- satb-07.pdf
```

**Expected behavior**
The book is transcribed and exported.

**Cause**
`StaffManager.getClosestStaff(Point2D)` returns null when no staff area contains the point, as its Javadoc says. `StaffManager.computeStaffArea` cuts each staff area to the width of its system, so a point in a page margin has no staff. Here, curves are found in the instrument names ("Soprano", "Alto", ...) to the left of the first system.

`ArcRetriever.isStaffArc` does not check for null. Once it does, the same null reaches `SlursBuilder.purgeStaffLines`, `SlurLinker.canBeOrphan` and `SegmentsBuilder`. There the exception is caught, but slur and wedge building is abandoned for the whole sheet.

**Audiveris environment:**
- Audiveris 5.11.0 (build 9e1e55cd), Windows 11, installer with its bundled runtime.
- Also on `development` at c5381487d, Ubuntu 24.04, Temurin 25.0.4.1, built from source.
- Both in batch mode.

A fix is ready: see the linked pull request.
