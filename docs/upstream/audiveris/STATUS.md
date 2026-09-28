# Audiveris contributions: status

Keep one row per bug (see `README.md`). "Reproduced" means on an upstream `development` build, not only on 5.11.0.

| # | Bug | Reproduced on `development` | Root cause | Upstream issue | Branch (`vilpter/audiveris`) | Pull request | State |
|---|---|---|---|---|---|---|---|
| 1 | CURVES: NPE in `ArcRetriever.isStaffArc` (`repro/satb-07.pdf`) | not yet | | none yet | | | to do |
| 2 | Headless Linux: `UnsatisfiedLinkError` escapes `WellKnowns.getGdkMaxScale` | not yet | `catch (Exception)` misses an `Error` | none yet | | | to do |
| 3 | Export produces nothing after a sheet fails to reload | not yet | see upstream thread | [#971](https://github.com/Audiveris/audiveris/issues/971) | | | to do |
| 4 | RHYTHMS: NPE in `Voices.refineSystem` (`repro/satb-01-scan-prepared.png`) | not yet | | none yet | | | to do |
| 5 | Small solo staff above a grand staff: systems split | needs a public repro first | | none yet | | | to do |

## Log

- 2026-09-27: plan written; fork `vilpter/audiveris` created; repro inputs for bugs 1 and 4 checked on Audiveris 5.11.0 (Windows): both crash as described.
