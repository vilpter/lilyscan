\version "2.26.0"

bassMusic = {
  % m. 1
  \clef bass \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { b,8 d8 fis,8 } c8 d8 fis2 |
  % m. 2
  a16 d16 a8~ a4 c'2 |
  % m. 3
  \tuplet 3/2 4 { e8 fisis8 a8 } g2 e16 g16 fis16 b,16 |
  % m. 4
  c4 b,8 a,16 b,16 c8 d16 e16~ e8 fis8 |
  % m. 5
  e4. g8 b8 g16 f16 e8. fis16 |
  % m. 6
  es4 d2 e8 c8 |
  % m. 7
  g4 e4. d8 ces8 b,8 |
  % m. 8
  c4 \tuplet 3/2 4 { fis8 e8 b8 } g2 | \bar "|."
}

bassVerseOne = \lyricmode {
  Ag -- nus De -- i qui tol --
  lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis Ag --
  nus De -- i qui tol --
  lis pec -- ca -- ta
}
