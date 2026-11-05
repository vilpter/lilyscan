<!-- Draft for Audiveris/audiveris issues. Not posted: needs the owner's OK. -->

**Title:** A keyboard's braced staves below other staves are exported as separate one-staff parts

**Describe the bug**
On a system where a keyboard instrument's two staves sit below one or more single staves
(a violin above a harpsichord; four voices above an accompaniment), Audiveris finds the brace
but exports the keyboard's lower staff as a part of its own. It is named "Voice" and has no
`<staves>2</staves>`. The keyboard part then holds only its upper staff. Downstream programs
see an extra one-staff instrument, and the keyboard has no left hand.

Audiveris 5.11.0 on the first page of each example below:

| Page | Expected parts | Exported parts |
|---|---|---|
| Bach, BWV 1014, Bach-Gesellschaft Bd. 9 (1860), p. 69: Violino, then Cembalo on a braced pair | Violino; Cembalo (2 staves) | Violino.; Cembalo.; Voice |
| "Lord, for Thy Tender Mercy's Sake", Church Music Society Reprints 18A, p. 1: S, A, T, B, then the accompaniment on a braced pair | 4 voices; Accompt. (2 staves) | Sorxuxo.; Auto.; (in Mar).; Voice; Voice; Voice |

On the Bach page the `.omr` shows the brace (`<brace shape="BRACE" grade="0.8">`) next to staves
2 and 3 of the first system. Each of the three staves is still its own `<part>`. So the brace
is recognized but not used to keep the two staves in one part. The vocal labels on the second
page are a separate OCR matter.

**To Reproduce**
Both scores are public domain on IMSLP:
- Bach-Gesellschaft Bd. 9, BWV 1014: <https://imslp.org/wiki/Special:ImagefromIndex/02242>,
  PDF page 3 (printed p. 69). The page image is 2480 × 3339, CCITT.
- Church Music Society Reprints 18A: <https://imslp.org/wiki/Special:ImagefromIndex/594593>,
  page 1. The page image is 1388 × 2048, JPEG.

Each page was extracted as a PNG at the scan's own pixel size (`pdftoppm -singlefile -png
-scale-to-x <width> -scale-to-y -1`), then:

```
Audiveris -batch -export -output out -- page.png
```

**Expected behavior**
The braced pair is exported as one part with two staves, under the name printed at the brace
(here "Cembalo." and the accompaniment).

**Reproduced on**
5.11.0 (release), and on `development` at 10d3c184b with #1132 applied. #1132 changes how
parts are matched across systems, not how staves are grouped into parts, and the result is the
same.

**Possibly related**
#1011 / #1012 (sheet scale from a minority staff size). Our earlier note on this problem was a
cue-size violin staff above a grand staff, where the system was also split in two. These two
pages have full-size staves and show only the grouping part of it.
