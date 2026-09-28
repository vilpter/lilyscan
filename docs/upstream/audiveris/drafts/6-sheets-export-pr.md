<!-- Draft pull request for Audiveris/audiveris (base: development). Not posted: needs the owner's OK.
     Branch: vilpter/audiveris fix/sheets-export-names, commit 8197b075f (not pushed yet). -->

**Title:** Name exported movements by their rank among the exported scores

**Bug**
In batch mode, exporting a sheet selection (`-sheets`) of a book with several movements writes every movement to `BOOK.mvtnull.mxl`. Each movement overwrites the previous one, so only the last is left. With the opus option, the export fails with `ZipException: duplicate entry: BOOK.mvtnull.xml` and leaves a broken `BOOK.opus.mxl`.

It is easy to hit: one invalid sheet fails the export of a whole book, so a natural workaround is to re-run with `-sheets` minus that sheet. And scanned books often have several movements, since each indented system starts one.

**Cause**
With `-sheets`, `CLI` transcribes and exports into a temporary list of scores (`scores = new ArrayList<>()` when `sheetIds != null`). `Score.getId()` is the score's rank in `book.getScores()`, so it is null for these scores. `Book.export`, `Book.getScoreExportPaths` and `OpusExporter.export` then name each one `BOOK.mvtnull`.

**Fix**
Name each score by its rank among the scores being exported, and add the `.mvt#` suffix only when more than one score is exported. That is one private helper, `Book.getScoreName`, used at both places in `Book`, plus one line in `OpusExporter`. Every other caller (GUI, plugins, and batch without `-sheets`) exports `book.getScores()`, where the rank is the id and the count is the book's, so their file names do not change.

The movements of a selection are numbered within the selection. A fresh `-sheets` run never reads the other sheets, so it cannot know their movements.

**To reproduce**
[two-movements.pdf](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/two-movements.pdf) is one page with two movements, engraved by LilyPond from [two-movements.ly](https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/two-movements.ly) (AGPL):

```
Audiveris -batch -export -sheets 1 -output out -- two-movements.pdf
```

**Tested** on Linux (JDK 25), comparing `development` at c5381487d with this branch.

| Command on `two-movements.pdf` | `development` | This branch |
|---|---|---|
| `-export` | `mvt1` (4/4, 13 measures), `mvt2` (3/4, 13) | identical files |
| `-export -sheets 1` | `mvtnull` only (movement 2) | `mvt1` (4/4, 13), `mvt2` (3/4, 13) |
| `-export -sheets 1`, opus | `ZipException`, 55-byte file | opus with `mvt1` and `mvt2` |

- **Page 2 of a two-page, four-movement book (`-sheets 2`):** `development` exports `mvtnull` only. This branch exports `mvt1` and `mvt2`, holding movements 3 and 4.
- **Nine other inputs without `-sheets`:** exported file names and contents are identical, apart from the encoding date. The inputs are generated solo, piano, quartet, SATB and lead-sheet pages. Two of them, a SATB page and a scan of one, crash on both builds for unrelated reasons, #1084 and a RHYTHMS NPE.
- **`./gradlew test`:** 216 tests, 0 failures.
