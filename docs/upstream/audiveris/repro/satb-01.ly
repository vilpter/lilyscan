\version "2.26.0"
% automatically converted by musicxml2ly from eval\corpus\seed\satb-01\ground_truth.musicxml
\pointAndClickOff

%% additional definitions required by the score:
D = \tweak Stem.direction #DOWN \etc
U = \tweak Stem.direction #UP \etc


\header {
  title = "Lilyscan satb-01"
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
PartPOneVoiceOne = \relative a' {
  \clef "treble" \numericTimeSignature \time 4/4 \key e \minor \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \U a8 [ \U b8 \U g8 ] }
  \D b8 ~ [ \D b8 ] a2 | % 1
  \U g16 [ \U e16 \U g8 ] fis4 d2 | % 2
  \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \U fis8 [ \U b8 \U d8 ] }
  c2 \D b16 [ \D c16 \D b16 \D c16 ~ ] | % 3
  c4 \D d8 [ \D b16 \D c16 ] \U b8 [ \U g16 \U fis16 ] \U g8 [ \U b8 ] | % 4
  fis'4. g8 \D fis8 [ \D e16 \D g16 ] \D fisis8. [ \D g16 ] | % 5
  fis!4 d2 \D e8 [ \D fisis8 ] | % 6
  d4 b4. d8 ~ \D d8 [ \D e8 ] | % 7
  fis4 \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \D e8 [ \D fis8 \D e8 ] }
  g2 \bar "|."
}

PartPOneVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  Ag -- nus De -- i \skip1 qui tol -- lis pec -- ca -- ta mun -- di mi -- se --
  re -- re no -- bis \skip1 Ag -- nus De -- i qui tol -- lis pec -- ca -- ta mun
  -- di mi -- se -- re -- re no -- bis Ag -- nus De -- i \skip1 qui tol -- lis
  pec -- ca -- ta
}

PartPTwoVoiceOne = \relative b' {
  \clef "treble" \numericTimeSignature \time 4/4 \key e \minor \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \D b8 [ \D g8 \D c8 ] }
  \U g8 [ \U e8 ] d2 | % 1
  \U c16 [ \U e16 \U c8 ] b4 d2 | % 2
  \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \U g8 [ \U a8 ~ \U a8 ] }
  g2 \U b16 [ \U as16 \U c,16 \U b16 ] | % 3
  d4 \U e8 [ \U g16 \U e16 ] \U fis8 [ \U d16 \U fis16 ] \U d8 [ \U c8 ] | % 4
  g4. c8 \D a'8 [ \D bis16 \D c16 ] \D a8. [ \D c16 ] | % 5
  b!4 g2 \U b8 [ \U a8 ] | % 6
  c4 a4. gis8 \U fis8 [ \U c8 ] | % 7
  g'!4 ~ \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \U g8 ~ [ \U g8 \U b,8 ] }
  g2 \bar "|."
}

PartPTwoVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  Ag -- nus De -- i qui tol -- lis pec -- ca -- ta mun -- di mi -- \skip1 se --
  re -- re no -- bis Ag -- nus De -- i qui tol -- lis pec -- ca -- ta mun -- di
  mi -- se -- re -- re no -- bis Ag -- nus De -- i qui tol -- lis pec -- \skip1
  \skip1 ca -- ta
}

PartPThreeVoiceOne = \relative as {
  \clef "treble_8" \numericTimeSignature \time 4/4 \key e \minor \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \U as8 [ \U g8 ~ \U g8 ] }
  \U e8 [ \U fis8 ] d2 | % 1
  \U fis16 [ \U d16 \U c8 ] d4 e2 | % 2
  \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \U d8 [ \U e8 \U fis8 ] }
  e2 \D b'16 [ \D fis16 \D c'16 \D e16 ] | % 3
  fis4 \D e8 [ \D d16 \D c16 ~ ] \D c8 [ \D a16 \D d16 ] \D b8 [ \D c8 ] | % 4
  e4. c8 \U b8 [ \U d,16 \U e16 ] \U g8. [ \U a16 ] | % 5
  b4 d2 ~ \D d8 [ \D e8 ] | % 6
  fis4 e4. fis8 \D e8 [ \D a,8 ] | % 7
  c4 \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \D fis,8 [ \D a8 \D fis'8 ] }
  e2 \bar "|."
}

PartPThreeVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  Ag -- nus \skip1 De -- i qui tol -- lis pec -- ca -- ta mun -- di mi -- se --
  re -- re no -- bis Ag -- nus De -- i \skip1 qui tol -- lis pec -- ca -- ta mun
  -- di mi -- se -- re -- re no -- \skip1 bis Ag -- nus De -- i qui tol -- lis
  pec -- ca -- ta
}

PartPFourVoiceOne = \relative b, {
  \clef "bass" \numericTimeSignature \time 4/4 \key e \minor \tweak
  TupletBracket.direction #UP \tuplet 3/2 {
    \U b8 [ \U d8 \U fis,8 ] }
  \U c'8 [ \U d8 ] fis2 | % 1
  \D a16 [ \D d,16 \D a'8 ~ ] a4 c2 | % 2
  \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \D e,8 [ \D fisis8 \D a8 ] }
  g2 \U e16 [ \U g16 \U fis16 \U b,16 ] | % 3
  c4 \U b8 [ \U a16 \U b16 ] \D c8 [ \D d16 \D e16 ~ ] \D e8 [ \D fis8 ] | % 4
  e4. g8 \D b8 [ \D g16 \D f16 ] \D e8. [ \D fis16 ] | % 5
  es4 d2 \D e8 [ \D c8 ] | % 6
  g'4 e4. d8 \U ces8 [ \U b8 ] | % 7
  c!4 \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \D fis8 [ \D e8 \D b'8 ] }
  g2 \bar "|."
}

PartPFourVoiceOneLyricsOne = \lyricmode {
  \set ignoreMelismata = ##t
  \set includeGraceNotes = ##t
  Ag -- nus De -- i qui tol -- lis pec -- ca -- \skip1 ta mun -- di mi -- se --
  re -- re no -- bis Ag -- nus De -- i qui tol -- lis \skip1 pec -- ca -- ta mun
  -- di mi -- se -- re -- re no -- bis Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta \skip1
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
