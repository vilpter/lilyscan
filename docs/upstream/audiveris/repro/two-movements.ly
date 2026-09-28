\version "2.26.0"
% Two movements on one page: each score's first system is indented, which Audiveris
% reads as the start of a movement.
\header { title = "Two movements" tagline = ##f }
\paper { #(set-paper-size "a4") indent = 15\mm }

\score {
  \header { piece = "I" }
  \relative c'' {
    \key g \major \time 4/4
    \repeat unfold 3 { g4 a b c | d2 b | c4 b a g | fis2 d | }
    g1 \bar "|."
  }
  \layout { }
}

\score {
  \header { piece = "II" }
  \relative c'' {
    \key d \major \time 3/4
    \repeat unfold 3 { d4 cis b | a2 fis4 | g4 fis e | d2. | }
    d2. \bar "|."
  }
  \layout { }
}
