\version "2.26.0"

altoMusic = {
  % m. 1
  \clef treble \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { b'8 g'8 c''8 } g'8 e'8 d'2 |
  % m. 2
  c'16 e'16 c'8 b4 d'2 |
  % m. 3
  \tuplet 3/2 4 { g'8 a'8~ a'8 } g'2 b'16 as'16 c'16 b16 |
  % m. 3
  \tuplet 3/2 4 { g'8 a'8~ a'8 } g'2 b'16 as'16 c'16 b16 |
  % m. 4
  d'4 e'8 g'16 e'16 fis'8 d'16 fis'16 d'8 c'8 |
  % m. 5
  g4. c'8 a'8 bis'16 c''16 a'8. c''16 |
  % m. 6
  b'4 g'2 b'8 a'8 |
  % m. 7
  c''4 a'4. gis'8 fis'8 c'8 |
  % m. 8
  g'4~ \tuplet 3/2 4 { g'8~ g'8 b8 } g2 | \bar "|."
}

altoVerseOne = \lyricmode {
  Ag -- nus De -- i qui tol --
  lis pec -- ca -- ta mun --
  di mi -- se -- re -- re no -- bis
  di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec -- ca --
  ta mun -- di mi -- se -- re -- re
  no -- bis Ag -- nus
  De -- i qui tol -- lis
  pec -- ca -- ta
}
