<!-- Draft for a pull request from vilpter/audiveris:fix/arc-outside-staff-areas into Audiveris/audiveris:development. Not opened: needs the owner's OK. Replace #N with the issue number. -->

**Title:** Do not assume a curve point lies in a staff area

Fixes #N

A staff area spans the height between its neighbouring staves but only the width of its system (`StaffManager.computeStaffArea`). So `getClosestStaff` returns null for a point in a page margin, as its Javadoc says. Curve building assumed a staff for every point, in four places:

- `ArcRetriever.isStaffArc`: an arc starting in margin text (here, the instrument names left of the first system) aborted CURVES, and with it the whole book. Such an arc cannot be a portion of staff line, so it now returns false.
- `SlursBuilder.purgeStaffLines`: a slur ending outside every staff area does not end on a staff line, so it is left to the other checks.
- `SlurLinker.canBeOrphan`: a legal orphan must end in the first or last measure of a staff, so an end outside every staff area cannot be one.
- `SegmentsBuilder`: an end outside every staff area is not within any staff's height.

The last three were caught by their builders, but each abandoned slur or wedge building for the whole sheet.

Tested with batch runs of `development` with and without this change:

- The page from the issue: before, CURVES fails and nothing is exported. After, the book completes with no exception, and the export has all 32 measures and 17 slurs. The page is dense and still reads imperfectly (141 of its 280 notes and rests), which is a separate matter.
- Six other one-page scores (solo, piano, SATB, string quartet, lead sheet): identical exports before and after (notes, slurs, measures).
- `./gradlew test`: 216 tests, no failures.
