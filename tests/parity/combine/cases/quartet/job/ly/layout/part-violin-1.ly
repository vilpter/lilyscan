\version "2.26.0"

\include "../parts/violin-1.ly"

\score {
  <<
    \new Staff \with { instrumentName = "Violin 1" shortInstrumentName = "Vln. 1" } \new Voice = "violinOneVoice" \transpose c' d' \violinOneMusic
  >>
  \layout { }
}
