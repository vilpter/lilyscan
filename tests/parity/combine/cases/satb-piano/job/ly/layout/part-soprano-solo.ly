\version "2.26.0"

\include "../parts/soprano-solo.ly"

\score {
  <<
    \new Staff \with { instrumentName = \markup \center-column { "Soprano" "solo" } } \new Voice = "sopranoSoloVoice" \sopranoSoloMusic
    \new Lyrics \lyricsto "sopranoSoloVoice" \sopranoSoloVerseOne
  >>
  \layout { }
}
