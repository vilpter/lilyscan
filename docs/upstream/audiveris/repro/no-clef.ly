\version "2.26.0"
% A test page for Audiveris: staves that print no clef (as on many drum charts).
% Lilyscan project content, AGPL-3.0-or-later.
\header { title = "No clef printed" tagline = ##f }
\paper { #(set-paper-size "letter") indent = 0 }
\layout { \context { \Staff \omit Clef } }
rhythm = \relative c'' {
  \time 4/4
  \repeat unfold 6 { c4 c8 c c4 c | c8 c c4 c2 | }
  \repeat unfold 4 { b4 b b8 b b4 | b2 b | }
}
\score { << \new Staff \rhythm \new Staff \transpose c' f, \rhythm >> \layout { } }
\score { \new Staff \rhythm \layout { } }
