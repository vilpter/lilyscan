# Audiveris contributions: status

Keep one row per bug (see `README.md`). "Reproduced" means on an upstream `development` build, not only on 5.11.0.

| # | Bug | Reproduced on `development` | Root cause | Upstream issue | Branch (`vilpter/audiveris`) | Pull request | State |
|---|---|---|---|---|---|---|---|
| 1 | CURVES: NPE in `ArcRetriever.isStaffArc` (`repro/satb-07.pdf`) | yes (c5381487d) | staff areas stop at the system's width, so `getClosestStaff` is null for margin points (instrument names); 4 call sites in `sheet/curve` assumed a staff | draft: `drafts/1-curves-issue.md` | [`fix/arc-outside-staff-areas`](https://github.com/vilpter/audiveris/tree/fix/arc-outside-staff-areas) | draft: `drafts/1-curves-pr.md` | fix ready; waiting for the owner's OK to post |
| 2 | Headless Linux: `UnsatisfiedLinkError` escapes `WellKnowns.getGdkMaxScale` | not yet | `catch (Exception)` misses an `Error` | none yet | | | to do |
| 3 | Export produces nothing after a sheet fails to reload | not yet | see upstream thread | [#971](https://github.com/Audiveris/audiveris/issues/971) | | | to do |
| 4 | RHYTHMS: NPE in `Voices.refineSystem` (`repro/satb-01-scan-prepared.png`) | yes (c5381487d) | | none yet | | | to do |
| 5 | Small solo staff above a grand staff: systems split | needs a public repro first | | none yet | | | to do |

## Log

- 2026-09-27: plan written; fork `vilpter/audiveris` created; repro inputs for bugs 1 and 4 checked on Audiveris 5.11.0 (Windows): both crash as described.
- 2026-09-27: development environment on a Linux box, contained in one directory (JDK 25, clone of the fork, Gradle cache, Audiveris settings). Bug 1 reproduced on `development`; fixed in four places; the page now exports. Six other pages export identically with and without the fix; `./gradlew test` passes (216 tests). Bug 4 reproduced on `development`. With bug 1 fixed, satb-07 also logs `IndexOutOfBoundsException` in `MeasureStack.getXOffset` (RHYTHMS, caught per measure stack): a possible further bug, not yet looked at.
