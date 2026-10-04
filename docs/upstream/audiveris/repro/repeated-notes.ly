\version "2.26.0"
% A test page for Audiveris: repeated notes on and between staff lines.
% Lilyscan project content, AGPL-3.0-or-later.
\header { title = "Repeated notes" tagline = ##f }
\paper { #(set-paper-size "letter") indent = 0 }
exercise = \relative c'' {
  \key d \major \time 4/4
  d4 d d d | b b b b | g g g g | e e e e |
  fis'4 fis fis fis | d d b2 | g4 g e2 | d'4 d d2 |
  b4 b g2 | e4 e e2 | a4 a fis2 | cis'4 cis a2 |
  d2 d | b b | g g | e e |
  fis'2 d4 d | b2 g4 g | e2 g4 b | d1 \bar "||"
  d4 d b b | g g e e | fis' fis d d | b b g g |
  a4 a a a | fis fis fis fis | cis' cis cis cis | e e e e |
  d4 d d d | b b b b | g g g g | e e e e | d1 \bar "|."
}
\score { \new Staff \with { instrumentName = "Violin" } \exercise \layout { } }
