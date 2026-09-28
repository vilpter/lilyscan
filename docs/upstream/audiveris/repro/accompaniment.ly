\version "2.26.0"
\header { title = "Accompaniment test" tagline = ##f }
\paper { #(set-paper-size "a4") indent = 12\mm }
#(set-global-staff-size 19)

violin = \relative c'' {
  \key a \major \time 4/4
  \repeat unfold 4 { a8 b cis d e4 e | fis8 e d cis b4 a | cis8 d e fis e4 cis | b8 cis b a gis4 a | }
  \bar "|."
}
upper = \relative c' {
  \key a \major \time 4/4
  \repeat unfold 4 { <cis e a>4 <cis e a> <d fis a> <d fis a> | <cis e a>2 <b d gis> | <cis e a>4 q <d fis b> q | <b e gis>2 <cis e a> | }
}
lower = \relative c {
  \clef bass \key a \major \time 4/4
  \repeat unfold 4 { a4 e' d a | a e' e, e' | a,4 e' b fis' | e e, a2 | }
}

\score {
  <<
    \new Staff \with { \magnifyStaff #2/3 instrumentName = "Violin" } \violin
    \new PianoStaff \with { instrumentName = "Piano" } <<
      \new Staff \upper
      \new Staff \lower
    >>
  >>
  \layout { }
}
