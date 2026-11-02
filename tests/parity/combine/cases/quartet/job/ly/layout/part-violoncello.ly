\version "2.26.0"

\include "../parts/violoncello.ly"

\score {
  <<
    \new Staff \with { instrumentName = "Violoncello" shortInstrumentName = "Vc." } \new Voice = "violoncelloVoice" \violoncelloMusic
  >>
  \layout { }
}
