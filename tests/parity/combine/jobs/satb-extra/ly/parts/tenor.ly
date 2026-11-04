\version "2.26.0"

tenorMusic = {
  % m. 1
  \clef "treble_8" \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { as8 g8~ g8 } e8 fis8 d2 |
  % m. 2
  fis16 d16 c8 d4 e2 |
  % m. 3
  \tuplet 3/2 4 { d8 e8 fis8 } e2 b16 fis16 c'16 e'16 |
  % m. 4
  fis'4 e'8 d'16 c'16~ c'8 a16 d'16 b8 c'8 |
  % m. 5
  e'4. c'8 b8 d16 e16 g8. a16 |
  % m. 6
  b4 d'2~ d'8 e'8 |
  % m. 7
  fis'4 e'4. fis'8 e'8 a8 |
  % m. 8
  c'4 \tuplet 3/2 4 { fis8 a8 fis'8 } e'2 | \bar "|."
}

tenorVerseOne = \lyricmode {
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
}
