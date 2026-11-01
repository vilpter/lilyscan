\version "2.26.0"

\header {
  title = "Lilyscan leadsheet-01"
  composer = "Generated"
  tagline = ##f
}

voiceMusic = {
  % m. 1
  \clef treble \key d \minor \numericTimeSignature \time 4/4 a'2 g'16 f'16 c'8 d'8~ d'8 |
  % m. 2
  f'16 d'16 bes'16 c''16 bes'8 r8 a'16~ a'16 beses'16 a'16 g'4 |
  % m. 3
  eis'8 r8 f'4 g'16 e'16 f'16 e'16 bes'16 d''16 c''8 |
  % m. 4
  d''4. c''8 a'8. g'16 e''8. d''16 |
  % m. 5
  c''8 d''8 f''4. g''8 \tuplet 3/2 4 { f''8 d''8 r8 } |
  % m. 6
  f''8 r8 g''16 f''16 g''16 f''16 g''16 f''16~ f''16 d''16 r16 gis''16 e''8 |
  % m. 7
  d''2 \tuplet 3/2 4 { f''8 d''8 f''8 } bes'16 a'16 f'8 |
  % m. 8
  as'8. f''16 e''2 \tuplet 3/2 4 { g'8 bes'8~ bes'8 } |
  % m. 9
  \tuplet 3/2 4 { fis''8 d''8 e''8 } f''8 gis''16 f''16 a'8 r16 g'16 f'16 e'16 g'16 a'16 |
  % m. 10
  bes'8 c''16 e''16 \tuplet 3/2 4 { d''8 e''8 g'8 } e'16 a'16 r16 c''16 gis''8 e''16 r16 |
  % m. 11
  c''16 a'16 bes'16 a'16 f''2 e''4 |
  % m. 12
  f''8. d''16 bes'8 c''8 r8 bes'8 a'16~ a'16 bes'16 c''16 | \bar "|."
}

voiceVerseOne = \lyricmode {
  A -- maz -- ing grace how
  sweet the sound that saved a wretch like me
  A -- maz -- ing grace how sweet the sound that
  saved a wretch like me A --
  maz -- ing grace how sweet the
  sound that saved a wretch like me A -- maz -- ing
  grace how sweet the sound that saved
  a wretch like me A --
  maz -- ing grace how sweet the sound that saved a wretch like
  me A -- maz -- ing grace how sweet the sound that saved
  a wretch like me A -- maz --
  ing grace how sweet the sound that saved
}

harmonies = \chordmode {
  e1 |  % m. 1
  g2:m7 d2:m7 |  % m. 2
  f1:m7 |  % m. 3
  e1:m7 |  % m. 4
  c2:sus4 a2:m7 |  % m. 5
  bes1:7 |  % m. 6
  f1:m7 |  % m. 7
  bes2:7 e2 |  % m. 8
  d2:m7 bes2:dim |  % m. 9
  f1 |  % m. 10
  c1:m7 |  % m. 11
  e1:7 |  % m. 12
}

\score {
  <<
    \new ChordNames \harmonies
    \new Staff \with { instrumentName = "Voice" shortInstrumentName = "Vo." } \new Voice = "voiceVoice" \voiceMusic
    \new Lyrics \lyricsto "voiceVoice" \voiceVerseOne
  >>
  \layout { }
  \midi { }
}
