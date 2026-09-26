\version "2.26.0"

\score {
  <<
    \new ChordNames \harmonies
    \new StaffGroup <<
      \new Staff \with { instrumentName = "Voice" shortInstrumentName = "Vo." } \new Voice = "voiceVoice" \voiceMusic
      \new Lyrics \lyricsto "voiceVoice" \voiceVerseOne
      \new Lyrics \lyricsto "voiceVoice" \voiceVerseTwo
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
