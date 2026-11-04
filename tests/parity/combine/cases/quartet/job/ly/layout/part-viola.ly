\version "2.26.0"

\include "../parts/viola.ly"

\score {
  <<
    \new Staff \with { instrumentName = "Viola" shortInstrumentName = "Vla." } \new Voice = "violaVoice" \violaMusic
  >>
  \layout { }
}
