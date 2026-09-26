\version "2.26.0"

pianoUpperMusic = {
  % m. 0
  \clef treble \key g \major \numericTimeSignature \time 4/4 \partial 4 r4 |
  % m. 1
  \bar ".|:" << { \voiceOne <g' b' d''>1 } \new Voice { \voiceTwo e'2 fis'2 } >> \oneVoice |
  % m. 2
  \time 3/4 \grace s16 R2. | \bar ":|."
  % m. 3
  \key f \major <f' a' c''>2. | \bar "|."
}

pianoLowerMusic = {
  % m. 0
  \clef bass \key g \major \numericTimeSignature \time 4/4 \partial 4 g,4 |
  % m. 1
  \bar ".|:" g,2 \clef treble b2 |
  % m. 2
  \clef bass \time 3/4 \grace s16 d2. | \bar ":|."
  % m. 3
  \key f \major f,2. | \bar "|."
}
