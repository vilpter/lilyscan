\version "2.26.0"

voiceMusic = {
  % m. 0
  \clef treble \key g \major \numericTimeSignature \time 4/4 \partial 4 d''4 |
  % m. 1
  \bar ".|:" g'2 \tuplet 3/2 4 { a'8 b'8 c''8 } d''4 |
  % m. 2
  \time 3/4 \grace e''16 d''4.~ d''8 b'4 | \bar ":|."
  % m. 3
  \key f \major f'2.\fermata | \bar "|."
}

voiceVerseOne = \lyricmode {
  O
  come ye faith -- ful joy --
  ful and
  tri
}

voiceVerseTwo = \lyricmode {
  Sing
  we
}
