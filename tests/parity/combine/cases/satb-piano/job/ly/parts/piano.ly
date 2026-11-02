\version "2.26.0"

pianoUpperMusic = {
  % m. 1
  \clef treble \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { b'8 g'8 c''8 } g'8 e'8 d'2 |
  % m. 2
  c'16 e'16 c'8 b4 d'2 |
  % m. 3
  \tuplet 3/2 4 { g'8 a'8~ a'8 } g'2 b'16 as'16 c'16 b16 |
  % m. 4
  \tuplet 3/2 4 { g'8 a'8~ a'8 } g'2 b'16 as'16 c'16 b16 |
  % m. 4
  d'4 e'8 g'16 e'16 fis'8 d'16 fis'16 d'8 c'8 |
  % m. 5
  g4. c'8 a'8 bis'16 c''16 a'8. c''16 |
  % m. 6
  b'4 g'2 b'8 a'8 |
  % m. 7
  c''4 a'4. gis'8 fis'8 c'8 |
  % m. 8
  g'4~ \tuplet 3/2 4 { g'8~ g'8 b8 } g2 | \bar "|."
}

pianoLowerMusic = {
  % m. 1
  \clef bass \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { <b,,, as>8 <d,, g~>8 <fis,,, g>8 } <c,, e>8 <d,, fis>8 <fis,, d>2 |
  % m. 2
  <a,, fis>16 <d,, d>16 <a,,~ c>8 <a,, d>4 <c, e>2 |
  % m. 3
  \tuplet 3/2 4 { <e,, d>8 <fisis,, e>8 <a,, fis>8 } <g,, e>2 <e,, b>16 <g,, fis>16 <fis,, c'>16 <b,,, e'>16 |
  % m. 4
  R1 |
  % m. 4
  <c,, fis'>4 <b,,, e'>8 <a,,, d'>16 <b,,, c'~>16 <c,, c'>8 <d,, a>16 <e,,~ d'>16 <e,, b>8 <fis,, c'>8 |
  % m. 5
  <e,, e'>4. <g,, c'>8 <b,, b>8 <g,, d>16 <f,, e>16 <e,, g>8. <fis,, a>16 |
  % m. 6
  <es,, b>4 <d,, d'~>2 <e,, d'>8 <c,, e'>8 |
  % m. 7
  <g,, fis'>4 <e,, e'>4. <d,, fis'>8 <ces,, e'>8 <b,,, a>8 |
  % m. 8
  <c,, c'>4 \tuplet 3/2 4 { <fis,, fis>8 <e,, a>8 <b,, fis'>8 } <g,, e'>2 | \bar "|."
}
