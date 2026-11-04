\version "2.26.0"

\score {
  <<
    \new StaffGroup <<
      \new Staff \with { instrumentName = "Violin 1" shortInstrumentName = "Vln. 1" } \new Voice = "violinOneVoice" \transpose c' d' \violinOneMusic
      \new Staff \with { instrumentName = "Violin II" shortInstrumentName = "Vln. II" } \new Voice = "violinIIVoice" \violinIIMusic
      \new Staff \with { instrumentName = "Viola" shortInstrumentName = "Vla." } \new Voice = "violaVoice" \violaMusic
      \new Staff \with { instrumentName = "Violoncello" shortInstrumentName = "Vc." } \new Voice = "violoncelloVoice" \violoncelloMusic
    >>
  >>
  \layout { }
  \midi { }
}
