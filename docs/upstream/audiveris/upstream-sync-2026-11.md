# Audiveris upstream sync, November 2026

The fork's `master` (5.11.0 + our fixes, at 01b84fa2b) took upstream's engine changes on `development` since 5.11.0, reviewed one by one, and two new fork fixes. The result is `vilpter/audiveris` `master-sync` at fcb0174d8 (77 commits on top of 01b84fa2b).

## Method

- **Review:** the 223 non-merge commits in `5.11.0..upstream/development` (2026-11-16). 109 touch engine code; each was taken, skipped (UI, translation, classifier training, platform, cosmetic) or later held back. Duplicates (the same change at two hashes) were taken once.
- **Cherry-picks:** in upstream order, with `-x`, in 16 groups of related commits, plus our fixes 14 and 15 as groups 17 and 18. Upstream commits keep their own trailers, including the `Co-Authored-By`/`Claude-Session` lines some contributors' commits carry (owner's decision, 2026-11-18).
- **Gate per group:** build and Audiveris unit tests; then `omr-regress` on 208 inputs: the 156 public seed/repertoire inputs (a guard) and 52 held-out OpenScore inputs (13 valid pieces, the judge), each compared with the previous group. Run-to-run noise on a quiet machine: 1 of 208 (seed/piano-03, which is also load-sensitive and was not used to judge).
- **When a group lost:** bisect (single commits or leave-one-out) and hold back the commit responsible; when a group exposed a crash or a split, fix it in the fork.

## Groups

| Group | Decision | Held-out exact / F1 (pts) | Notes |
|---|---|---|---|
| 1 stems, robustness | accepted | -0.25 / -0.04 | two correctness fixes together drop a chord note on one photo |
| 2 staff lines, bars, repeats | accepted without 3742503bc | +0.12 / +0.12 | 8ba6bd3b4 (RunTable reload) moved in first: the group exposed that reload bug |
| 3 header clefs (#998 + our #1110) | accepted | -0.06 / +0.02 | exposed a MeasuresBuilder crash: fix 14 |
| 4 scale, skeleton, divisions | accepted | +0.08 / +0.01 |  |
| 5 note heads | accepted | +0.00 / +0.01 |  |
| 6 mid-staff clef recovery | held back | -0.15 / -0.30 | invents clefs (a phantom G2 clef in a cello part) |
| 7 residual beams | accepted | +0.25 / +0.27 | haydn-op74-1 scan 56 -> 79% |
| 8 counts, curves, cross heads | accepted | -0.08 / -0.01 | recovers seed/satb-07 (our #1084) |
| 9 endings, tuplets | accepted without 1d2573a79 | +0.50 / +0.25 | 1d2573a79 drops bracket-less tuplets (16 clean pages) |
| 10 template distance | test only | n/a | superseded by group 5; test taken as upstream adapted it |
| 11 symbols, time, wedges | accepted | +0.00 / +0.00 |  |
| 12 crash fixes, our #1109 | accepted without 82d954de3, f3818efc4 | +0.00 / +0.00 | drum ghost-note support misreads dotted piano chords |
| 13 starting bar lines | accepted | +0.00 / +0.00 |  |
| 14 beams, rest positions, stems | accepted | +0.08 / +0.05 | rests exported at their standard MusicXML position |
| 15 whole heads, bent lines, ledgers | accepted | +1.25 / +1.30 | 5e889e3b1 also split two pages into extra parts: fix 15 |
| 16 text "J", tied heads | accepted | +0.00 / +0.00 | test-only conflicts left out |
| 17 fix 14 (ours) | accepted | +0.00 / +0.00 | removes the crash path |
| 18 fix 15 (ours) | accepted | +0.00 / -0.10 | part splits gone |

Overall, fork `master` (01b84fa2b) against `master-sync` (fcb0174d8) on the same 208 inputs: **held-out +1.90 pts exact measures, +1.86 pts note F1 (20 better, 2 worse); public +1.72 / +2.23 (29 better, 9 worse, 3 recovered).**

## Fork fixes made during the sync

- **Fix 14** (`fix/column-cell-occupied`, 6298c1533): two bar groups of one staff fell within one column's tolerance; the second was left out of the column but stayed in its staff's list, and `MeasuresBuilder.buildStaffBarlines` failed. `Column.canJoin` now refuses an occupied staff cell.
- **Fix 15** (`fix/staff-extension-all-lines`, a681a2e5f): 5e889e3b1 follows staff lines that bend near a page border, but also followed the strokes of a part name ("B.") printed on a bass staff, 2 interlines into the margin, and the parts split (a 4-part SATB page exported as 8). A staff extension must now reach 4 interlines; real bends reach 10 to 50.

## Second round (2026-11-19)

Upstream commits after the first round, `e262ee72a..bb4130db4`: 17 non-merge commits, tested the same way on top of `fcb0174d8`.

| Group | Commits | Result vs the previous group | Decision |
|---|---|---|---|
| G19 text-metronome | `de6055cbb`, `3dd47c8ea` | no score change | taken |
| G20 beam-peak | `9e83f71a7` | held-out unchanged; public -0.44 exact (seed/solo-03 photo 93.8% -> 25.0%) | held back |
| G21 rescue-staff | `848bc2434` | held-out -2.06 exact (schubert-mignon photo 58.3% -> 0%, one held-out and six public photos stop exporting) | held back |
| G22 markers-export | `4504774db`, `9c833ec42` | coda/segno exported; no score change | taken |
| G23 print | `97473b1be` | no score change | taken |
| G24 classifier | `d05f00c82` | 158 of 200 inputs change; held-out -0.44 exact (mozart-k387 pdf 84.4% -> 59.4%), public -0.57 | held back |

Skipped: four i18n commits (`072066291`, `22eb5dd04`, `9462b5012`, `33e1a7722`), `ec6a2c7cf` (needs i18n resources the fork lacks), `a7403ad5c` (debug prints and test files the fork lacks), `810618e2c` (regression references), `da6443aaa` (FreeBSD build).

Fork `master` fcb0174d8 -> 5aec223d5. Held-out results are identical to the first round's. The next sync starts after `bb4130db4`.

## Every reviewed commit

| # | Commit | Author | Subject | Decision | Group | Reason |
|---|---|---|---|---|---|---|
| 1 | `42dcc44e6` | Hervé Bitteur | Manual merge of PR #597 from JLLeitschuh to fix Temporary File Information Disclosure Vulnerability | skip |  | training only (classifier temp files) |
| 2 | `4a4709837` | John O'Laughlin | Fix beam look-ahead in CLinker.expand reading the current item | take | G01-stems-robustness |  |
| 3 | `3658175c5` | Hervé Bitteur | Fix for resources available even in batch mode | skip |  | i18n plumbing |
| 4 | `99a3ac5ea` | Hervé Bitteur | Bug fix for issue #975, caused by totally black areas | take | G01-stems-robustness |  |
| 5 | `ab9f02fea` | Hervé Bitteur | Bug fix for issue #975, with better context information | take | G01-stems-robustness |  |
| 6 | `433942549` | Claude | Fix non-transitive comparator in StemBuilder.sortItems | take | G01-stems-robustness |  |
| 7 | `616c3a630` | Hervé Bitteur | ScaleItem class now uses a ResourceBundle rather than the Application ResourceMap, unavailable in ba | skip |  | i18n plumbing |
| 8 | `246d6784e` | Alfonso Acosta | Fix NPE in StemBuilder.getLength for stem profiles above maxStemProfile | take | G01-stems-robustness |  |
| 9 | `3eb7c8552` | Alfonso Acosta | Fix "no such edge in graph" when an inter removal cascades | take | G01-stems-robustness |  |
| 10 | `7a117c06e` | Alfonso Acosta | Fix book storage when a folder name ends with "!" | take | G01-stems-robustness |  |
| 11 | `3742503bc` | Alfonso Acosta | Trim a staff cluster by line spacing, not by length alone | hold | G02-staff-bars-repeats | net loss on held-out and public photos (variants 2026-11-18); fixes #990 only |
| 12 | `013eb76f9` | Alfonso Acosta | Let the dots alone spell a repeat, whatever the group's bar count | take | G02-staff-bars-repeats |  |
| 13 | `4212d55d0` | Alfonso Acosta | Pair a small staff's repeat dots by that staff's own interline | take | G02-staff-bars-repeats |  |
| 14 | `3fb3902d4` | Alfonso Acosta | Keep the whole bar line group that opens a system | take | G02-staff-bars-repeats |  |
| 15 | `3652aec1b` | Alfonso Acosta | Keep a header clef only when it really is one | take | G03-header-clef-998 |  |
| 16 | `4c0ad914b` | Alfonso Acosta | Read a repeat count printed as "x4" or "4x" and export it as times | take | G02-staff-bars-repeats |  |
| 17 | `17b186679` | Alfonso Acosta | Hold original glyphs strongly so their IDs do not follow the collector | take | G02-staff-bars-repeats |  |
| 18 | `e83741b99` | Alfonso Acosta | Keep a system's first measure when a bar of staff follows the header | take | G02-staff-bars-repeats |  |
| 19 | `756144b39` | Alfonso Acosta | Let a multiple rest support the measure count above it | take | G02-staff-bars-repeats |  |
| 20 | `9d8c6f821` | Alfonso Acosta | Keep the gap between two consecutive staves in the curve skeleton | take | G04-scale-skeleton-export |  |
| 21 | `3d5415e07` | Alfonso Acosta | Scale a sheet on its dominant staff size, not its largest | take | G04-scale-skeleton-export |  |
| 22 | `d04a6a022` | Hervé Bitteur | Bugfix for issue #940, while manually inserting compound notes | skip |  | manual-editing UI path |
| 23 | `79a641734` | Hervé Bitteur | Bugfix for issue #978, use a time-out while checking GitHub site for a new Audiveris version | skip |  | GUI version check |
| 24 | `ec2b2cd23` | Hervé Bitteur | Bugfix for issue #982, that might export a divisions value of zero | take | G04-scale-skeleton-export |  |
| 25 | `7cd501187` | Hervé Bitteur | Even cleaner bug fix for issue #982, by considering a quarter upfront regardless of page content | take | G04-scale-skeleton-export |  |
| 26 | `5bf8610e6` | SilverGreen93 | small ui fixes | skip |  | UI |
| 27 | `2ef1a4fe4` | marecabo | Marshal a pruned copy of the parameters instead of nullifying the live structure | take | G04-scale-skeleton-export |  |
| 28 | `fb5bfcbdf` | marecabo | Synchronize the shared SimpleDateFormat of Constant.Date | take | G04-scale-skeleton-export |  |
| 29 | `741ee3dbc` | SilverGreen93 | Fix language list not entirely visible | skip |  | UI |
| 30 | `aecacb4ed` | SilverGreen93 | Fix checkboxes in Languages dialog | skip |  | UI |
| 31 | `d7f450a3b` | SilverGreen93 | Adjust color of log lines and tab titles | skip |  | UI |
| 32 | `9a1ded990` | Hervé Bitteur | Lazy elaboration of music and text fonts | skip |  | startup performance via GUI font code, not accuracy |
| 33 | `b1d60243e` | Alfonso Acosta | Match a drum staff's half heads, not only its filled quarters | take | G05-heads |  |
| 34 | `3b52248cc` | Alfonso Acosta | Refuse a head whose own ink does not fill the template that found it | take | G05-heads |  |
| 35 | `af6327328` | Alfonso Acosta | Read a clefless five-line staff as a drum staff when drumNotation is set | take | G05-heads |  |
| 36 | `7d3a86a78` | Alfonso Acosta | Match a head whose strokes the engraving draws thinner than the font | take | G05-heads |  |
| 37 | `9812247dd` | Alfonso Acosta | Let the glyph classifier train without a display | skip |  | classifier training |
| 38 | `6c92c5951` | Alfonso Acosta | Bound the beams and flags that can shorten a note | take | G05-heads |  |
| 39 | `ea53c6ee5` | ctuijn | Recover mid-staff clef changes missed by the general symbol search | hold | G06-midstaff-clefs | invents phantom mid-staff clefs (quartet-02 score.png, schumann-c photo); net loss held-out -0.15/-0.30 (2026-11-18) |
| 40 | `226f2d1a9` | ctuijn | Fix false-positive and missed-detection cases in mid-staff clef recovery | hold | G06-midstaff-clefs | invents phantom mid-staff clefs (quartet-02 score.png, schumann-c photo); net loss held-out -0.15/-0.30 (2026-11-18) |
| 41 | `49028f1ad` | ctuijn | Don't adopt a beam-side seed that doesn't point out of the beam group | take | G07-residual-beams |  |
| 42 | `5f7142d9b` | ctuijn | Recover a beam SpotsBuilder never produced a candidate for, and evict the spurious head it caused | take | G07-residual-beams |  |
| 43 | `cf1fd7213` | ctuijn | Calibrate ResidualBeamBuilder's thresholds against two more confirmed cases | take | G07-residual-beams |  |
| 44 | `0d2769dc0` | ctuijn | Detect and recover stacked double beams, not just one beam | take | G07-residual-beams |  |
| 45 | `4d8433dd1` | ctuijn | Recover residual beams with no trace at all, and fix a tip-tolerance miss | take | G07-residual-beams |  |
| 46 | `65d068a47` | ctuijn | Widen maxTipDistanceForSuspicion to catch a same-shape double-beam pair | take | G07-residual-beams |  |
| 47 | `3fc85f0b3` | ctuijn | Never pair two stems whose heads share a column | take | G07-residual-beams |  |
| 48 | `371d0c9e8` | Alfonso Acosta | Read a measure count of more than one digit | take | G08-counts-curves-cross-heads |  |
| 49 | `993c932b0` | Alfonso Acosta | Keep a measure count linked to whichever sign candidate survives | take | G08-counts-curves-cross-heads |  |
| 50 | `fc908cdbc` | vilpter | Do not assume a curve point lies in a staff area | take | G08-counts-curves-cross-heads |  |
| 51 | `8ae287c16` | Alfonso Acosta | Keep a second beam thickness only in proportion to the second staff size | take | G08-counts-curves-cross-heads |  |
| 52 | `796cf50b6` | Alfonso Acosta | Find the stem a cross head's tip is drawn from | take | G08-counts-curves-cross-heads |  |
| 53 | `8197b075f` | vilpter | Name exported movements by their rank among the exported scores | take | G08-counts-curves-cross-heads |  |
| 54 | `c4c25ae90` | Alfonso Acosta | Find a multiple rest among the beams of either thickness | take | G08-counts-curves-cross-heads |  |
| 55 | `56f1cf30d` | Alfonso Acosta | Bridge an ending segment across a beam that touches it | take | G09-endings-tuplets |  |
| 56 | `72ce91595` | Alfonso Acosta | Refuse an ending that is a side of a box | take | G09-endings-tuplets |  |
| 57 | `55a9947bd` | Alfonso Acosta | Embrace the run of chords whose durations make up the tuplet | take | G09-endings-tuplets |  |
| 58 | `2c8cbc9a1` | Alfonso Acosta | Refuse a tuplet sign drawn over a note head | take | G09-endings-tuplets |  |
| 59 | `e11261fe6` | Alfonso Acosta | Refuse a tuplet number drawn among the chords it would embrace | take | G09-endings-tuplets |  |
| 60 | `1d2573a79` | Alfonso Acosta | Ask for a bracket around a tuplet number over unbeamed chords | hold | G09-endings-tuplets | drops bracket-less tuplets over unbeamed chords (16 clean public pages worse); leave-one-out 2026-11-18 |
| 61 | `c764ff878` | Alfonso Acosta | Refuse a tuplet sign that is a letter of a word | take | G09-endings-tuplets |  |
| 62 | `a327121fe` | Lucas0623z | Simplify binary template distance calculation with exact-score tests | take | G10-template-distance |  |
| 63 | `db15aee16` | gaoyuindeu | Export wedges of chords without time offset | take | G11-symbols-time-wedges |  |
| 64 | `59e942eaa` | Alfonso Acosta | Skip a clef candidate that lies within no staff | hold | G06-midstaff-clefs | invents phantom mid-staff clefs (quartet-02 score.png, schumann-c photo); net loss held-out -0.15/-0.30 (2026-11-18) |
| 65 | `b97209d34` | Alfonso Acosta | Tell a tuplet 6 from a 3 by its hole | take | G09-endings-tuplets |  |
| 66 | `bdfb735bf` | Alfonso Acosta | Give every chord of a tuplet the same support | take | G09-endings-tuplets |  |
| 67 | `aa31e2916` | Hervé Bitteur | Preferences dialog: better handling of plugin option, plus tips for reset vs cancel | skip |  |  |
| 68 | `502938de8` | Alfonso Acosta | Keep the room above a page's first staff in the curve skeleton | take | G11-symbols-time-wedges |  |
| 69 | `ec3bb3476` | Alfonso Acosta | Check as a symbol a one-letter word amid punctuation | take | G11-symbols-time-wedges |  |
| 70 | `8294911e5` | Hervé Bitteur | PR #1013, manually ported to development branch and limited to indented systems | take | G11-symbols-time-wedges |  |
| 71 | `82d954de3` | Alfonso Acosta | Read a note head drawn between brackets as the head it is | hold | G12-bracket-heads-crashes | drum ghost-note support; misreads dotted piano chords (seed/piano-03 png 100 -> 87.5%, reproducible); no ghost notes in our repertoire (2026-11-18) |
| 72 | `f3818efc4` | Alfonso Acosta | Mark a head drawn between brackets | hold | G12-bracket-heads-crashes | drum ghost-note support; misreads dotted piano chords (seed/piano-03 png 100 -> 87.5%, reproducible); no ghost notes in our repertoire (2026-11-18) |
| 73 | `247a3711f` | vilpter | Do not let a part name on one staff hide the start of its system | take | G12-bracket-heads-crashes |  |
| 74 | `9d238dab4` | Hervé Bitteur | Stronger algorithm to determine the default target folder of a book | skip |  | GUI default folder |
| 75 | `8ba6bd3b4` | hibikiwtnb | RunTable: reload a table that has no sequence | take | G02-staff-bars-repeats | moved first into G02: G02 exposes the RunTable reload bug it fixes |
| 76 | `eef64e574` | hibikiwtnb | EndingsBuilder: no crash on an ending line shorter than a pixel | take | G12-bracket-heads-crashes |  |
| 77 | `f113a2d95` | hibikiwtnb | MetronomeInter: no crash when the classifier returns fewer evaluations | take | G12-bracket-heads-crashes |  |
| 78 | `32340ec56` | Alfonso Acosta | Let a grouped barline veto a time signature in an indented header | take | G11-symbols-time-wedges |  |
| 79 | `f211587e1` | hibikiwtnb | TextBuilder: no crash on a text line left with no role | take | G12-bracket-heads-crashes |  |
| 80 | `ecbba0ea8` | Hervé Bitteur | Protection of starting barlines | take | G13-starting-barlines |  |
| 81 | `10d3c184b` | Hervé Bitteur | Protection of starting barlines, continued | take | G13-starting-barlines |  |
| 82 | `3b383554c` | Hervé Bitteur | ShapeClassifier now exports enforceReproducibility(), meant for the whole training process | skip |  | classifier training |
| 83 | `6384ccbc6` | Alfonso Acosta | Let a training run be repeated, through ShapeClassifier.enforceReproducibility | skip |  | classifier training |
| 84 | `899505037` | Hervé Bitteur | Seeded repository selection | skip |  | classifier training |
| 85 | `63b774b14` | Alfonso Acosta | Say the classifier's reproducible seed is independent of the split's | skip |  | classifier training |
| 86 | `9e2cc15ac` | Alfonso Acosta | Let the learning rate decay over the epochs | skip |  | classifier training |
| 87 | `dcb6bae77` | Hervé Bitteur | Adapt ProjectEncoding to all OSes | skip |  | Windows/OS encoding of project files; Lilyscan is Linux |
| 88 | `a66a9c53a` | SilverGreen93 | Fix Constants zebra color | skip |  | UI |
| 89 | `6c0f889f6` | SilverGreen93 | Support fractional HiDPI scaling on Linux Wayland | skip |  | UI (FlatLaf/Wayland) |
| 90 | `718881303` | SilverGreen93 | Allow removal of installed OCR langauges | skip |  | UI |
| 91 | `ec6e9ea3f` | SilverGreen93 | Fix resource injection in the languages selector dialog | skip |  | UI |
| 92 | `c85e96921` | SilverGreen93 | Fix NPE when forcing a step on a sheet with no step done yet | take | G12-bracket-heads-crashes |  |
| 93 | `483c23efc` | SilverGreen93 | Correctly identify thicker beams | take | G14-beams-rests-stems |  |
| 94 | `1aba2b1a9` | SilverGreen93 | Export whole and half rests at their standard MusicXML position | take | G14-beams-rests-stems |  |
| 95 | `50924eda1` | SilverGreen93 | Ignore OCR test in unit-tests. Fix slurs detected as J | take | G16-conflicts-text-heads |  |
| 96 | `79815807c` | Hervé Bitteur | More positive message in Versions | skip |  | i18n |
| 97 | `8fc4cfb5e` | Hervé Bitteur | L18N for SheetStub, added German (de) in supported locales | skip |  | i18n |
| 98 | `cd96893a5` | SilverGreen93 | Choose export format: xml or mxl | skip |  | UI/export dialog |
| 99 | `2820aa36b` | SilverGreen93 | Recognize whole notes from their ink, before weak heads are purged | take | G15-whole-heads-lines-ledgers |  |
| 100 | `3da17ee97` | SilverGreen93 | Find the brace that sits in the group of bars opening a system | take | G13-starting-barlines |  |
| 101 | `de638eac2` | SilverGreen93 | Keep stems off the bar lines that connect two staves | take | G14-beams-rests-stems |  |
| 102 | `72fd8f3ef` | Hervé Bitteur | Removed useless closing brace in Scale.toString() | skip |  | cosmetic |
| 103 | `e32572357` | Hervé Bitteur | Fixed wrong bundle name in FilterDescriptor and ClustersRetriever | skip |  | i18n |
| 104 | `5e889e3b1` | SilverGreen93 | Follow staff lines that bend away near a staff end | take | G15-whole-heads-lines-ledgers |  |
| 105 | `8a0bb42d5` | SilverGreen93 | Keep a bar connector as long as the two bar lines it joins | take | G15-whole-heads-lines-ledgers |  |
| 106 | `9de2165db` | SilverGreen93 | Grant a wider delta to ledgers as wide as a single head's | take | G15-whole-heads-lines-ledgers |  |
| 107 | `193df1e28` | SilverGreen93 | Decide between head candidates tied on adjacent pitches by their ink | take | G16-conflicts-text-heads |  |
| 108 | `3a91202bf` | SilverGreen93 | Cut the line stubs off a whole head's ink before checking its shape | take | G15-whole-heads-lines-ledgers |  |
| 109 | `54dd5420e` | SilverGreen93 | Keep a stem and its heads when the beam at its end turns out false | take | G14-beams-rests-stems |  |
| + | `fix/weak-` | vilpter | Fix 7 / #1110: needed with #998 (n=15) | take | G03-header-clef-998 | repairs the regression #998 introduces |
| + | `6298c1533` | vilpter | Fix 14: keep one group per staff in a barline column (joplin photo crash exposed by #998) | take | G17-column-fix | our fix, branch fix/column-cell-occupied |
| + | `a681a2e5f` | vilpter | Fix 15: extend a staff only by the length a bend toward the page border leaves (G15 part split) | take | G18-staff-extension-fix | our fix, branch fix/staff-extension-all-lines |
