\version "2.26.0"

\score {
  <<
    \new StaffGroup <<
      \new Staff \with { instrumentName = \markup \center-column { "Soprano" "solo" } } \new Voice = "sopranoSoloVoice" \sopranoSoloMusic
      \new Lyrics \lyricsto "sopranoSoloVoice" \sopranoSoloVerseOne
      \new PianoStaff \with {
        instrumentName = "Piano"
        shortInstrumentName = "Pno."
      } <<
        \new Staff \new Voice = "pianoUpperVoice" \pianoUpperMusic
        \new Staff \new Voice = "pianoLowerVoice" \pianoLowerMusic
      >>
    >>
  >>
  \layout { }
  \midi { }
}
