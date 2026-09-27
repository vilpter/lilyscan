\version "2.26.0"

\include "../parts/voice.ly"
\include "../chords.ly"

\score {
  <<
    \new ChordNames \harmonies
    \new Staff \with { instrumentName = "Voice" shortInstrumentName = "Vo." } \new Voice = "voiceVoice" \voiceMusic
    \new Lyrics \lyricsto "voiceVoice" \voiceVerseOne
    \new Lyrics \lyricsto "voiceVoice" \voiceVerseTwo
  >>
  \layout { }
}
