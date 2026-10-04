\version "2.26.0"
% automatically converted by musicxml2ly from eval\corpus\seed\quartet-06\ground_truth.musicxml
\pointAndClickOff

%% additional definitions required by the score:
D = \tweak Stem.direction #DOWN \etc
U = \tweak Stem.direction #UP \etc


\header {
  title = "Lilyscan quartet-06"
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
PartPOneVoiceOne = \relative beses' {
  \clef "treble" \time 2/4 \key bes \major \tweak TupletBracket.direction #UP
  \tuplet 3/2 {
    \D beses8 [ \D a8 \D bes8 ] }
  \U g8 [ \U f16 \U es16 ] | % 1
  g16 r16 f8 \U d8 [ \U c8 ] | % 2
  bes4 \U c16 [ \U bes16 ] r8 | % 3
  c4. bes8 | % 4
  d8 r8 r16 \U bes16 [ \U c16 \U d16 ~ ] | % 5
  \U d16 [ \U g16 \U f16 \U d16 ] \U c8 ~ [ \U c8 ] | % 6
  \U bes16 [ \U c16 \U bes16 ] r16 \U c8 [ \U as16 \U bes16 ] | % 7
  a!2 \bar "|."
}

PartPTwoVoiceOne = \relative f' {
  \clef "treble" \time 2/4 \key bes \major \U f16 [ \U g16 \U f8 ] \U d16 [ \U
  bes16 \U d16 \U bes16 ] | % 1
  \U a16 [ \U bes16 \U g'16 \U bes16 ] c4 | % 2
  \D d8 [ \D c8 ] bes16 r16 \U g16 [ \U fes16 ] | % 3
  d'4 \D bes8. [ \D c16 ] | % 4
  \D a'8 [ \D bes8 ] \D c16 [ \D bes16 \D f8 ] | % 5
  \D es8. [ \D f16 ] \D g16 [ \D bes,16 \D g8 ] | % 6
  es'2 | % 7
  \D ges8 [ \D a8 ] \D d,8 [ \D c8 ] \bar "|."
}

PartPThreeVoiceOne = \relative es' {
  \clef "alto" \time 2/4 \key bes \major \tweak TupletBracket.direction #UP
  \tuplet 3/2 {
    \D es8 [ \D d8 ] r8 }
  \D es8. ~ [ \D es16 ] | % 1
  \D a8 ~ [ \D a16 \D ges16 ] \D a16 [ \D g16 \D as16 \D d,16 ] | % 2
  \D es8. [ \D g16 ] \D es8 [ \D f8 ] | % 3
  \D a8 [ \D f8 ~ ] \D f8 [ \D es16 \D d16 ] | % 4
  \D c16 [ \D g16 \D es'8 ] \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \D g8 [ \D f8 \D es8 ] }
  | % 5
  \D g8 ~ [ \D g8 ] \D eses16 [ \D f16 \D bes,16 ] r16 | % 6
  \tweak TupletBracket.direction #UP \tuplet 3/2 {
    des8 r8 bes8 }
  \D fes'16 [ \D d16 \D g16 \D bes16 ] | % 7
  a4. g8 \bar "|."
}

PartPFourVoiceOne = \relative ces, {
  \clef "bass" \time 2/4 \key bes \major \U ces16 [ \U dis16 \U f8 ] \U c16 [ \U
  d16 \U c16 \U d16 ] | % 1
  \U c8 [ \U es8 ] \U c'8 [ \U a8 ] | % 2
  bes4 \U c16 [ \U bes16 \U a16 \U g16 ] | % 3
  a8 r8 \tweak TupletBracket.direction #UP \tuplet 3/2 {
    \U d,8 [ \U f8 \U es8 ] }
  | % 4
  f4. a8 ~ | % 5
  a2 | % 6
  f'8 r16 d16 \U bes8 [ \U g8 ] | % 7
  \U f16 [ \U g16 \U f8 ] r8 c'8 \bar "|."
}


% The score definition
\score {
  <<
    \new Staff = "P1" <<
      \set Staff.instrumentName = "Violin I"
      \set Staff.shortInstrumentName = "Vln. I"
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPOneVoiceOne" {
          \PartPOneVoiceOne
        }
      >>
    >>
    \new Staff = "P2" <<
      \set Staff.instrumentName = "Violin II"
      \set Staff.shortInstrumentName = "Vln. II"
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPTwoVoiceOne" {
          \PartPTwoVoiceOne
        }
      >>
    >>
    \new Staff = "P3" <<
      \set Staff.instrumentName = "Viola"
      \set Staff.shortInstrumentName = "Vla."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPThreeVoiceOne" {
          \PartPThreeVoiceOne
        }
      >>
    >>
    \new Staff = "P4" <<
      \set Staff.instrumentName = "Violoncello"
      \set Staff.shortInstrumentName = "Vc."
      \context Staff <<
        \override Staff.BarLine.allow-span-bar = ##f
        \mergeDifferentlyDottedOn
        \mergeDifferentlyHeadedOn
        \context Voice = "PartPFourVoiceOne" {
          \PartPFourVoiceOne
        }
      >>
    >>
  >>
  \layout {}
  % To create MIDI output, uncomment the following line:
  % \midi { \tempo 4 = 100 }
}


\paper { page-count = #1 }
