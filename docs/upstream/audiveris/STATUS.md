# Audiveris contributions: status

Keep one row per bug (see `README.md`). "Reproduced" means on an upstream `development` build, not only on 5.11.0.

| # | Bug | Reproduced on `development` | Root cause | Upstream issue | Branch (`vilpter/audiveris`) | Pull request | State |
|---|---|---|---|---|---|---|---|
| 1 | CURVES: NPE in `ArcRetriever.isStaffArc` (`repro/satb-07.pdf`) | yes (c5381487d) | staff areas stop at the system's width, so `getClosestStaff` is null for margin points (instrument names); 4 call sites in `sheet/curve` assumed a staff | none: the report is in the pull request | [`fix/arc-outside-staff-areas`](https://github.com/vilpter/audiveris/tree/fix/arc-outside-staff-areas) | [#1084](https://github.com/Audiveris/audiveris/pull/1084) | open, awaiting review |
| 2 | Headless Linux: `UnsatisfiedLinkError` escapes `WellKnowns.getGdkMaxScale` | not yet | `catch (Exception)` misses an `Error` | none yet | | | to do |
| 3 | Export produces nothing after a sheet fails to reload | not yet | see upstream thread | [#971](https://github.com/Audiveris/audiveris/issues/971) | | | to do |
| 4 | RHYTHMS: NPE in `Voices.refineSystem` (`repro/satb-01-scan-prepared.png`) | yes (c5381487d) | each part's measures come from its own top staff's barlines (`MeasuresBuilder.buildPartMeasures`); a barline found on one part's staff only leaves a stack without a measure for the others (repro: system 1 has 2, 2, 2, 3 measures in 3 stacks) | draft: `drafts/4-rhythms-issue.md` (asks the maintainers which fix they prefer) | | | diagnosed; waiting for the owner's OK to post the issue |
| 5 | Small solo staff above a grand staff: systems split | needs a public repro first | | none yet | | | to do |
| 6 | Batch export with `-sheets` keeps only the last movement (`repro/two-movements.pdf`) | yes (c5381487d) | with `-sheets`, `CLI` exports a temporary score list, so `Score.getId()` is null and every movement is written to `BOOK.mvtnull.mxl` (opus export fails outright) | none: the report is in the pull request draft | [`fix/sheets-export-names`](https://github.com/vilpter/audiveris/tree/fix/sheets-export-names) | [#1088](https://github.com/Audiveris/audiveris/pull/1088) | open, awaiting review |

## Log

- 2026-09-27: plan written; fork `vilpter/audiveris` created; repro inputs for bugs 1 and 4 checked on Audiveris 5.11.0 (Windows): both crash as described.
- 2026-09-27: development environment on a Linux box, contained in one directory (JDK 25, clone of the fork, Gradle cache, Audiveris settings). Bug 1 reproduced on `development`; fixed in four places; the page now exports. Six other pages export identically with and without the fix; `./gradlew test` passes (216 tests). Bug 4 reproduced on `development`. With bug 1 fixed, satb-07 also logs `IndexOutOfBoundsException` in `MeasureStack.getXOffset` (RHYTHMS, caught per measure stack): a possible further bug, not yet looked at.
- 2026-09-27: pull request Audiveris/audiveris#1084 opened (owner's OK). It carries the bug report itself (trace, repro link, versions), so no separate issue was opened; `drafts/1-curves-issue.md` was not posted.
- 2026-09-28: bug 4 diagnosed on `development`: parts of one system can get different measure counts, since measures are built per part from that part's barlines, and `refineSystem` assumes a measure per part in every stack. It hit 2 of 118 real scanned string parts (a 7-page score and a 1-page part). The fix touches measure building, so the issue is drafted to agree on an approach first.
- 2026-09-28: bug 6 found: Lilyscan's retry with `-sheets` (after a failed page) kept only the last movement of 2 real scanned books. Reproduced with a one-page, two-movement LilyPond PDF on 5.11.0 and on `development`; cause in `CLI`'s temporary score list. Issue drafted with a suggested fix; Lilyscan no longer uses `-sheets`.
- 2026-09-28: bug 6 fixed on `fix/sheets-export-names`: movements named by their rank among the exported scores. With `-sheets`, `development` writes `mvtnull` only and the opus export fails with a duplicate zip entry; the fix exports every movement. Without `-sheets`, 10 inputs export identical files on both builds; `./gradlew test` passes (216). The issue draft was folded into a pull request draft that carries the report.
- 2026-09-28: pull request Audiveris/audiveris#1088 opened for bug 6 (owner's OK), from `drafts/6-sheets-export-pr.md`.
