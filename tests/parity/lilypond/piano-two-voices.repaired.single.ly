\version "2.26.0"

\header {
  title = "Lilyscan piano-01"
  composer = "Generated"
  tagline = ##f
}

pianoUpperMusic = {
  % m. 1
  \clef treble \numericTimeSignature \time 4/4 << { \voiceOne <g' f''>8 %{ ?? conf=0.39 bbox=p1:(527,399) %} d''8 %{ ?? conf=0.46 bbox=p1:(572,419) %} f''8 %{ ?? conf=0.46 bbox=p1:(627,399) %} } \new Voice { \voiceTwo s4 d''2 %{ ?? conf=0.46 bbox=p1:(676,419) %} b'4 %{ ?? conf=0.46 bbox=p1:(796,440) %} } \new Voice { \voiceThree s4 f'2 %{ ?? conf=0.46 bbox=p1:(676,471) %} g'4 %{ ?? conf=0.45 bbox=p1:(796,461) %} } >> \oneVoice s4 |
  % m. 2
  c''8 a'8 f'4 e'4 f'16 a'16 b'16 g''16 |
  % m. 3
  \set Timing.measureLength = #3/4 %{ ?? rhythm: 3/4 of 1 %} << { \voiceOne f''4 %{ ?? conf=0.50 bbox=p1:(1358,399) %} g''8 a''8 %{ ?? conf=0.50 bbox=p1:(1493,377) %} a''4 } \new Voice { \voiceTwo c''2 d''4 } \new Voice { \voiceThree s2 s16 s64 s1*1/192 <d'' g''>8 %{ ?? conf=0.44 bbox=p1:(1629,388) %} b''16 } >> \oneVoice |
  % m. 4
  \set Timing.measureLength = #1 c'''8. f''16 g''16~ g''16 f''16~ f''16 b''16 c'''16~ c'''8 b''8. g''16 |
  % m. 5
  << { \voiceOne b''8 r16 c'''16 f''4. e''8 g''4 } \new Voice { \voiceTwo a'2 c''4 c''4 } >> \oneVoice |
  % m. 6
  f''8 a''8 r16 c'''16 a''16~ a''16~ a''8. r16 b''16 des''16 e''8 |
  % m. 7
  << { \voiceOne r8. %{ ?? conf=0.47 bbox=p1:(1422,868) %} f''16 a''8~ a''8 f''8 } \new Voice { \voiceTwo s2 e''4 c''4 } >> \oneVoice s4. |
  % m. 8
  g''4 \tuplet 3/2 4 { f''8 g''8 b''8 } a''2 |
  % m. 9
  g''8. f''16~ f''8 g''8 f''16 d''16 ges'8 b'4~ |
  % m. 10
  b'8 e'16 d'16 r4 e'8. d'16 \tuplet 3/2 4 { e'8 d'8 bis'8 } |
  % m. 11
  g'8 a'8 b'2 a'16 b'16 c''8 |
  % m. 12
  << { \voiceOne <g' e''>8 %{ ?? conf=0.23 bbox=p1:(1818,1277) %} d''8 %{ ?? conf=0.30 bbox=p1:(1872,1287) %} e''8 %{ ?? conf=0.31 bbox=p1:(1927,1277) %} a'2 %{ ?? conf=0.32 bbox=p1:(1981,1318) %} } \new Voice { \voiceTwo s4. c''4 %{ ?? conf=0.30 bbox=p1:(1980,1298) %} } \new Voice { \voiceThree s2 f''8 %{ ?? conf=0.30 bbox=p1:(2072,1267) %} r8 %{ ?? conf=0.24 bbox=p1:(2137,1261) %} r4 %{ ?? conf=0.25 bbox=p1:(2196,1245) %} } \new Voice { \voiceFour s2. f'4 %{ ?? conf=0.30 bbox=p1:(2198,1339) %} } >> \oneVoice s8 | \bar "|."
}

pianoLowerMusic = {
  % m. 1
  \clef bass \numericTimeSignature \time 4/4 <e, g, b,>4 <d, f, a,>4 <f, a,>2 |
  % m. 2
  <a, c e>4 <g, b, d>4 e,2 |
  % m. 3
  \set Timing.measureLength = #3/4 %{ ?? rhythm: 3/4 of 1 %} <d, f, a,>4 \tuplet 3/2 1 { <e, g, b,>2 <b, d f>4 } |
  % m. 4
  \set Timing.measureLength = #1 <g, b,>2 <c, e, g,>4 <f, a,>4 |
  % m. 5
  <a, c>4 b,2 d,4 |
  % m. 6
  <g, b,>4 <e, g, b,>2 <f, a,>4 |
  % m. 7
  <e, g, b,>4 f,4 <e, g, b,>2 |
  % m. 8
  <f, a,>2 e,2 |
  % m. 9
  <d, f, a,>2 <c, e, g,>4 e,4 |
  % m. 10
  <d, f, a,>2 <b, d>2 |
  % m. 11
  a,4 e,2 g,4 |
  % m. 12
  <a, c>2 c2 | \bar "|."
}

\score {
  <<
    \new PianoStaff \with {
      instrumentName = "Piano"
      shortInstrumentName = "Piano"
    } <<
      \new Staff \new Voice = "pianoUpperVoice" \pianoUpperMusic
      \new Staff \new Voice = "pianoLowerVoice" \pianoLowerMusic
    >>
  >>
  \layout { }
  \midi { }
}
