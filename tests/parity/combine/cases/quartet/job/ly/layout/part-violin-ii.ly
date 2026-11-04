\version "2.26.0"

\include "../parts/violin-ii.ly"

\score {
  <<
    \new Staff \with { instrumentName = "Violin II" shortInstrumentName = "Vln. II" } \new Voice = "violinIIVoice" \violinIIMusic
  >>
  \layout { }
}
