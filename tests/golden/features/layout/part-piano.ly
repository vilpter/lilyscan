\version "2.26.0"

\include "../parts/piano.ly"
\include "../chords.ly"

\score {
  <<
    \new ChordNames \harmonies
    \new PianoStaff \with {
      instrumentName = "Piano"
      shortInstrumentName = "Pno."
    } <<
      \new Staff \new Voice = "pianoUpperVoice" \pianoUpperMusic
      \new Staff \new Voice = "pianoLowerVoice" \pianoLowerMusic
    >>
  >>
  \layout { }
}
