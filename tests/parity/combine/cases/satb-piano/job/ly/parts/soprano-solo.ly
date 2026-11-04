\version "2.26.0"

sopranoSoloMusic = {
  % m. 1
  \clef treble \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { a'8 b'8 g'8 } b'8~ b'8 a'2 |
  % m. 2
  g'16 e'16 g'8 fis'4 d'2 |
  % m. 3
  \tuplet 3/2 4 { fis'8 b'8 d''8 } c''2 b'16 c''16 b'16 c''16~ |
  % m. 4
  R1 |
  % m. 4
  c''4 d''8 b'16 c''16 b'8 g'16 fis'16 g'8 b'8 |
  % m. 5
  fis''4. g''8 fis''8 e''16 g''16 fisis''8. g''16 |
  % m. 6
  fis''4 d''2 e''8 fisis''8 |
  % m. 7
  d''4 b'4. d''8~ d''8 e''8 |
  % m. 8
  fis''4 \tuplet 3/2 4 { e''8 fis''8 e''8 } g''2 | \bar "|."
}

sopranoSoloVerseOne = \lyricmode {
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis Ag --
  nus De -- i qui
  tol -- lis pec -- ca -- ta
}
