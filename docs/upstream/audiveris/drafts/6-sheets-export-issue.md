<!-- Draft for Audiveris/audiveris issues. Not posted: needs the owner's OK. -->

**Title:** Batch export with `-sheets` writes every movement to `BOOK.mvtnull.mxl`, keeping only the last

**Describe the bug**
In batch mode, `-sheets` combined with `-export` on a book with several movements writes every movement to the same file, `BOOK.mvtnull.mxl`. Each movement overwrites the one before, so only the last movement is left. The log shows it:

```
INFO  Book 543  | Created scores: [{Score null}, {Score null}]
INFO  ScoreExporter 164  | Score two-movements.mvtnull exported to .../two-movements.mvtnull.mxl
INFO  ScoreExporter 164  | Score two-movements.mvtnull exported to .../two-movements.mvtnull.mxl
```

Without `-sheets`, the same book exports `BOOK.mvt1.mxl` and `BOOK.mvt2.mxl`, as expected.

This matters to anyone using `-sheets` to skip a page that fails. One invalid sheet fails the whole book's export, so re-running without it is the natural workaround. And Audiveris starts a movement at every indented system, so scanned books often have several movements. We lost two thirds of a 7-page score this way before noticing.

**To Reproduce**
A one-page PDF with two movements, engraved by LilyPond from `two-movements.ly` (public, AGPL):
<https://github.com/vilpter/lilyscan/raw/main/docs/upstream/audiveris/repro/two-movements.pdf>

```
Audiveris -batch -export -output out1 -- two-movements.pdf
Audiveris -batch -export -sheets 1 -output out2 -- two-movements.pdf
```

`out1` has `two-movements.mvt1.mxl` (4/4, 13 measures) and `two-movements.mvt2.mxl` (3/4, 13 measures). `out2` only has `two-movements.mvtnull.mxl`, holding movement 2. The same happens on 5.11.0 (Windows) and on `development` at c5381487d (Linux).

**Cause**
With `-sheets`, `CLI` transcribes and exports into a temporary list of scores (`scores = new ArrayList<>()` when `sheetIds != null`). `Score.getId()` is the score's position in `book.getScores()`, so it returns null for scores in that temporary list. `Book.isMultiMovement()` still reports true, since it looks at `book.getScores()`. So `Book.getScoreExportPaths` and `Book.export` build the name `bookName + ".mvt" + null` for every movement. `OpusExporter.export` names its entries the same way, so `-option ...useOpus=true` does not help.

**Suggested fix**
When exporting a list of scores, name the movements by their position in that list (`theScores.indexOf(score) + 1`), and decide whether the book has several movements from `theScores.size() > 1`. That applies in `Book.getScoreExportPaths`, `Book.export` and `OpusExporter.export`. Alternatively, `Score.getId()` could fall back to the position in the list it was created in. We are happy to send a pull request for whichever you prefer.

**Environment**
- Audiveris 5.11.0 (Windows 11) and `development` at c5381487d (Linux, JDK 25)
- Batch mode, `-export`, `-sheets`
