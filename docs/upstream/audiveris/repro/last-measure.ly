\version "2.26.0"
% A test page for Audiveris: a string trio whose last measure holds whole notes
% before the final bar line.
% Lilyscan project content, AGPL-3.0-or-later.
\header { title = "Last measure" tagline = ##f }
\paper { #(set-paper-size "letter") indent = 0 }
violin = \relative c'' {
  \key g \major \time 4/4
  g4 b d b | c2 a | b4 g a fis | g1 |
  d'4 c b a | b2 g | a4 b c a | d1 |
  e4 d c b | c2 e | d4 b a fis | g2 d |
  b'4 a g fis | e2 c' | b4 g a fis | g1 \bar "|."
}
viola = \relative c' {
  \clef alto \key g \major \time 4/4
  d4 g fis g | e2 fis | g4 d d d | d1 |
  g4 a g fis | g2 d | fis4 g a fis | fis1 |
  g4 b a g | g2 c | b4 g fis d | d2 b |
  g'4 fis e d | c2 e | d4 b c d | b1 \bar "|."
}
cello = \relative c {
  \clef bass \key g \major \time 4/4
  g4 g' d g | a,2 d | g,4 b d d, | g1 |
  b4 a g d' | g,2 b | d4 g fis d | d1 |
  c4 g' a e | a,2 c | d4 g d d, | g2 g' |
  e4 fis g b, | c2 a | d4 e d d, | g1 \bar "|."
}
\score {
  \new StaffGroup <<
    \new Staff \with { instrumentName = "Violin" } \violin
    \new Staff \with { instrumentName = "Viola" } \viola
    \new Staff \with { instrumentName = "Cello" } \cello
  >>
  \layout { }
}
