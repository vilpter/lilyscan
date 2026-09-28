\version "2.26.0"
% automatically converted by musicxml2ly from eval\corpus\seed\satb-07\ground_truth.musicxml
\pointAndClickOff

%% additional definitions required by the score:
D = \tweak Stem.direction #DOWN \etc
U = \tweak Stem.direction #UP \etc


\header {
  title = "Lilyscan satb-07"
  composer = Generated
  "id: software" = "music21 v.10.5.0"
}
#(set-global-staff-size 19.916929133858268)
\paper {
}
\layout {
  \context {
    \Staff
    printKeyCancellation = ##f
  }
  \context {
    \Score
    autoBeaming = ##f
  }
}
PartPOneVoiceOne = \relative d'' {
  \clef "treble" \numericTimeSignature \time 4/4 \key g \major \D d8. [ \D g,16
  ] \U b8 [ \U a16 \U g16 ] b4 \D c8 [ \D d16 \D c16 ] | % 1
  \D e8 [ \D fis8 ] \D d8 [ \D c8 ] d4. c8 | % 2
  \U e,16 [ \U b'16 \U c8 ] \U a8 [ \U fis8 ] \U e16 [ \U g16 \U a8 ] \D e'8 [
  \D fis8 ] | % 3
  e4 \D fis16 [ \D g16 \D fis8 ] \D g8 [ \D e8 ] \tweak TupletBracket.direction
  #UP \tuplet 3/2 {
    \D g8 [ \D b,8 \D c8 ] }
  | % 4
  \D d16 [ \D c16 \D b8 ] \U a8 [ \U d,8 ] \U fis8 [ \U e16 \U fis16 ] \U e8 [
  \U d16 \U g16 ] | % 5
  \U fis16 [ \U e16 \U des16 \U c16 ] e2 \U c16 [ \U e16 \U fis16 \U g16 ] | % 6
  \U a16 [ \U b16 \U fis16 ~ \U fis16 ] \U e16 [ \U fis16 \U e8 ] fis2 | % 7
  g4 \U d8. [ \U c16 ] \U d8 [ \U c8 ] \U e16 [ \U fis16 \U g8 ] \bar "|."
}

PartPOneVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  A -- maz -- ing grace how sweet the sound that saved a wretch like me A -- maz
  -- ing grace how sweet the sound that saved a wretch like me A -- maz -- ing
  grace how sweet the sound that saved a wretch like me A -- maz -- ing grace
  how sweet the sound that saved a wretch like me A -- \skip1 maz -- ing grace
  how sweet the sound that saved a wretch like
}

PartPTwoVoiceOne = \relative fis' {
  \clef "treble" \numericTimeSignature \time 4/4 \key g \major \U fis8. [ \U d16
  ] \U c8 ~ [ \U c16 \U d16 ] c4 \U d8 [ \U e16 \U d16 ] | % 1
  \U b8 [ \U g8 ] \U a8 [ \U c8 ] b4. c8 | % 2
  \U d16 [ \U c16 \U e8 ] \U g8 [ \U fis8 ] \U a,16 [ \U b16 \U a8 ] \U g8 [ \U
  b8 ] | % 3
  ces4 \U e16 [ \U g16 ~ \U g8 ] \U fis8 [ \U b,8 ] \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \U d8 [ \U cis8 \U des8 ] }
  | % 4
  \U a16 [ \U b16 \U a8 ] \U bes8 [ \U c!8 ] \U b8 [ \U c16 \U e16 ] \U d!8 [ \U
  e16 \U c16 ~ ] | % 5
  \U c16 [ \U b16 \U fis'16 \U g16 ] b2 \U e,16 [ \U d16 \U b16 \U a16 ] | % 6
  \U c16 [ \U e16 \U c16 \U a'16 ] \D c16 [ \D b16 \D c8 ] b2 ~ | % 7
  b4 \U e,8. [ \U a,16 ] \U b8 [ \U g'8 ] \D b16 [ \D g16 \D b8 ] \bar "|."
}

PartPTwoVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  A -- maz -- ing \skip1 grace how sweet the sound that saved a wretch like me A
  -- maz -- ing grace how sweet the sound that saved a wretch like \skip1 me A
  -- maz -- ing grace how sweet the sound that saved a wretch like me A --
  \skip1 maz -- ing grace how sweet the sound that saved a wretch like me A --
  maz -- ing \skip1 grace how sweet the sound that saved
}

PartPThreeVoiceOne = \relative ais {
  \clef "treble_8" \numericTimeSignature \time 4/4 \key g \major \U ais8. [ \U g16
  ] \U fis8 [ \U d'16 \U c16 ] d4 \U b8 [ \U d,16 \U fis16 ] | % 1
  \U g8 [ \U fis8 ] \U e8 [ \U d8 ] c4. e8 | % 2
  \U a16 [ \U c16 \U bes8 ] \D d8 [ \D fis8 ] \D d16 [ \D b16 \D d8 ] \D fis8 [
  \D g8 ] | % 3
  fis4 \D c16 [ \D b16 \D d8 ] \U g,8 [ \U fis8 ] \tweak TupletBracket.direction
  #UP \tuplet 3/2 {
    \U e8 [ \U d8 \U fis8 ] }
  | % 4
  \U g16 [ \U fis16 \U ais8 ] \U fis8 [ \U e8 ] \D b'8 [ \D d16 \D g16 ] \D c,8
  [ \D b16 \D dis16 ] | % 5
  \U b16 [ \U c16 \U e,16 \U g16 ] a!2 \U fis16 [ \U e16 \U f16 ~ \U f16 ] | % 6
  \U g16 [ \U a16 \U b16 \U g16 ] \D a16 [ \D e'16 ~ \D e8 ] g,2 | % 7
  e'4 \D ges8. [ \D fis16 ] \D g8 [ \D e8 ] \D g16 [ \D fis16 \D e8 ] \bar "|."
}

PartPThreeVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  A -- maz -- ing grace how sweet the sound that saved a wretch like me A -- maz
  -- ing grace how sweet the sound that saved a wretch like me A -- maz -- ing
  grace how sweet the sound that saved a wretch like me A -- maz -- ing grace
  how sweet the sound that saved a \skip1 wretch like me A -- maz -- ing \skip1
  grace how sweet the sound that saved a wretch
}

PartPFourVoiceOne = \relative ais, {
  \clef "bass" \numericTimeSignature \time 4/4 \key g \major \U ais8. [ \U fis16
  ] \U c'8 [ \U fis,16 \U a16 ] fis'4 \D g8 [ \D fis16 \D a16 ] | % 1
  \D fis8 [ \D a8 ] \D d,8 ~ [ \D d8 ] b4. d8 | % 2
  \D c16 [ \D d16 \D e8 ] \U a,8 [ \U fis8 ] \U g16 ~ [ \U g16 \U e8 ] \U fis8 [
  \U eis8 ] | % 3
  fis4 \U ges16 [ \U e!16 ~ \U e8 ] \U fis8 [ \U g8 ] \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \U bes8 [ \U c8 \U d8 ] }
  | % 4
  \D fis16 [ \D g16 ~ \D g8 ] \D e8 [ \D c8 ] \U e,8 [ \U c'16 \U d16 ] \D b'!8
  [ \D d,16 \D fis16 ] | % 5
  \D g16 [ \D b16 \D a16 \D g16 ] e2 \U c16 [ \U fis,16 \U c'16 \U b16 ] | % 6
  \U a16 [ \U b16 \U c16 \U e16 ] \U g,16 [ \U fis16 \U a8 ] fis2 ~ | % 7
  fis4 ~ \U fis8. [ \U e16 ] \U fis8 [ \U g8 ] \U a16 [ \U fis16 \U a8 ] \bar
  "|."
}

PartPFourVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  A -- maz -- ing grace how sweet the sound that saved a wretch \skip1 like me A
  -- maz -- ing grace how sweet \skip1 the sound that saved a wretch \skip1 like
  me A -- maz -- ing grace how \skip1 sweet the sound that saved a wretch like
  me A -- maz -- ing grace how sweet the sound that saved a wretch like me A --
  maz -- \skip1 \skip1 ing grace how sweet the sound
}


% The score definition
\score {
  <<
    \new Staff = "P1" <<
      \set Staff.instrumentName = "Soprano"
      \set Staff.shortInstrumentName = "S."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPOneVoiceOne" {
          \PartPOneVoiceOne
        }
        \new Lyrics \lyricsto "PartPOneVoiceOne" {
          \PartPOneVoiceOneLyricsOne
        }
      >>
    >>
    \new Staff = "P2" <<
      \set Staff.instrumentName = "Alto"
      \set Staff.shortInstrumentName = "A."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPTwoVoiceOne" {
          \PartPTwoVoiceOne
        }
        \new Lyrics \lyricsto "PartPTwoVoiceOne" {
          \PartPTwoVoiceOneLyricsOne
        }
      >>
    >>
    \new Staff = "P3" <<
      \set Staff.instrumentName = "Tenor"
      \set Staff.shortInstrumentName = "T."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPThreeVoiceOne" {
          \PartPThreeVoiceOne
        }
        \new Lyrics \lyricsto "PartPThreeVoiceOne" {
          \PartPThreeVoiceOneLyricsOne
        }
      >>
    >>
    \new Staff = "P4" <<
      \set Staff.instrumentName = "Bass"
      \set Staff.shortInstrumentName = "B."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPFourVoiceOne" {
          \PartPFourVoiceOne
        }
        \new Lyrics \lyricsto "PartPFourVoiceOne" {
          \PartPFourVoiceOneLyricsOne
        }
      >>
    >>
  >>
  \layout {}
  % To create MIDI output, uncomment the following line:
  % \midi { \tempo 4 = 100 }
}


\paper { page-count = #1 }
