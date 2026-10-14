\version "2.26.0"
% A test page for Audiveris: a string trio named in full on its first system and by
% the usual abbreviations on the following ones ("Vln. I" for "Violin I").
% Lilyscan project content, AGPL-3.0-or-later.
\header { title = "Part names" tagline = ##f }
\paper { #(set-paper-size "letter") }
one = \relative c'' {
  \key g \major \time 4/4 \autoLineBreaksOff
  g4 a b c | d2 b | c4 b a g | fis1 | \break
  g4 a b c | d2 b | c4 b a fis | g1 | \break
  b4 c d e | d2 b | a4 b c a | g1 | \break
  b4 c d e | d2 b | a4 fis e fis | g1 \bar "|."
}
two = \relative c' {
  \key g \major \time 4/4
  d4 fis g a | b2 g | a4 g fis d | d1 |
  b4 d g a | b2 g | a4 g fis d | d1 |
  g4 a b c | b2 g | fis4 g a fis | d1 |
  g4 a b c | b2 g | fis4 d c d | b1 \bar "|."
}
three = \relative c' {
  \clef alto \key g \major \time 4/4
  b4 d d e | g2 d | e4 d d c | a1 |
  b4 d d e | g2 d | e4 d d c | b1 |
  d4 e g g | g2 d | d4 d e d | b1 |
  d4 e g g | g2 d | d4 b a a | g1 \bar "|."
}
\score {
  <<
    \new Staff \with { instrumentName = "Violin I" shortInstrumentName = "Vln. I" } \one
    \new Staff \with { instrumentName = "Violin II" shortInstrumentName = "Vln. II" } \two
    \new Staff \with { instrumentName = "Viola" shortInstrumentName = "Vla." } \three
  >>
}
