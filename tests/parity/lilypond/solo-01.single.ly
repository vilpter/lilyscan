\version "2.26.0"

\header {
  title = "Lilyscan solo-01"
  composer = "Generated"
  tagline = ##f
}

fluteMusic = {
  % m. 1
  \clef treble \key g \minor \numericTimeSignature \time 4/4 bes'4 a'4. g'8 c''16 r16 bes'8 |
  % m. 2
  a'16 g'16~ g'16 es'16~ es'4 f'4.~ f'8 |
  % m. 3
  r4 g'4~ g'4 f'4~ |
  % m. 4
  f'8. es'16 f'2 d'16 es'16 d'8 |
  % m. 5
  es'8 des'8 es'8 d'16 r16 \tuplet 3/2 4 { es'8 d'8 e'8 } r8. g'16 |
  % m. 6
  bes'2 d''16~ d''16 es''16 ges'16 beses'4~ |
  % m. 7
  \tuplet 3/2 4 { beses'8 d''8 c''8 } es''4. d''8 a''8 r8 |
  % m. 8
  f''4 es''16 g'16 f'8 dis'8 f'8 d''8 bes'16 r16 |
  % m. 9
  a'2~ a'4 c''8. d''16 |
  % m. 10
  \tuplet 3/2 4 { bes'8 c''8 d''8 } es''4. d''8 es''8. r16 |
  % m. 11
  f''2 a''8. f''16 d''4 |
  % m. 12
  eses''4 g''2 a''8~ a''8 |
  % m. 13
  bes''4 a''2 r16 g''16 a''8~ |
  % m. 14
  a''8 f''16 es''16 \tuplet 3/2 4 { f''8 es''8 f''8 } a''8 fis''16 g''16 bes'8~ bes'8 |
  % m. 15
  a'4 g'8 f'16 g'16 f'8 g'8 a'4 |
  % m. 16
  r8. g'16 a'4 g'4. es''8 | \bar "|."
}

\score {
  <<
    \new Staff \with { instrumentName = "Flute" shortInstrumentName = "Fl." } \new Voice = "fluteVoice" \fluteMusic
  >>
  \layout { }
  \midi { }
}
