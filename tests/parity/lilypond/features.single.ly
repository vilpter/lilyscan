\version "2.26.0"

\header {
  title = "Feature Test"
  composer = "Lilyscan tests"
  tagline = ##f
}

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

harmonies = \chordmode {
  s4 |  % m. 0
  g2 d2:7 |  % m. 1
  \grace s16 s2. |  % m. 2
  s2. |  % m. 3
}

mergeFullBarRests =
#(define-music-function (music) (ly:music?)
   (define (name-of m) (ly:music-property m 'name))
   (define (plain-rest? m)
     (and (eq? (name-of m) 'MultiMeasureRestMusic)
          (null? (ly:music-property m 'articulations))))
   (define (bar-check? m) (memq (name-of m) '(BarCheck BarCheckEvent)))
   (define (merge elements)
     (let loop ((in elements) (out '()))
       (cond
        ((null? in) (reverse out))
        ((plain-rest? (car in))
         (let* ((first (car in)) (d (ly:music-property first 'duration)))
           (let run ((rest (cdr in)) (n 1))
             (let ((next (and (pair? rest) (bar-check? (car rest)) (cdr rest))))
               (if (and (pair? next) (plain-rest? (car next))
                        (equal? (ly:music-property (car next) 'duration) d))
                   (run (cdr next) (1+ n))
                   (begin
                     (if (> n 1)
                         (set! (ly:music-property first 'duration)
                               (ly:make-duration (ly:duration-log d) (ly:duration-dot-count d)
                                                 (* n (ly:duration-scale d)))))
                     (loop rest (cons first out))))))))
        (else (loop (cdr in) (cons (car in) out))))))
   (music-map
    (lambda (m)
      (if (eq? (name-of m) 'SequentialMusic)
          (set! (ly:music-property m 'elements) (merge (ly:music-property m 'elements))))
      m)
    (ly:music-deep-copy music)))

\book {
  \bookOutputSuffix "score"
  \score {
    <<
      \new ChordNames \harmonies
      \new StaffGroup <<
        \new Staff \with { instrumentName = "Voice" shortInstrumentName = "Vo." } \new Voice = "voiceVoice" \voiceMusic
        \new Lyrics \lyricsto "voiceVoice" \voiceVerseOne
        \new Lyrics \lyricsto "voiceVoice" \voiceVerseTwo
        \new PianoStaff \with {
          instrumentName = "Piano"
          shortInstrumentName = "Pno."
        } <<
          \new Staff \new Voice = "pianoUpperVoice" \pianoUpperMusic
          \new Staff \new Voice = "pianoLowerVoice" \pianoLowerMusic
        >>
      >>
    >>
    \layout { }
    \midi { }
  }
}

\book {
  \bookOutputSuffix "voice"
  \header { instrument = "Voice" }
  \score {
    <<
      \new ChordNames \harmonies
      \new Staff \with { instrumentName = "Voice" shortInstrumentName = "Vo." } \new Voice = "voiceVoice" \mergeFullBarRests \voiceMusic
      \new Lyrics \lyricsto "voiceVoice" \voiceVerseOne
      \new Lyrics \lyricsto "voiceVoice" \voiceVerseTwo
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "piano"
  \header { instrument = "Piano" }
  \score {
    <<
      \new ChordNames \harmonies
      \new PianoStaff \with {
        instrumentName = "Piano"
        shortInstrumentName = "Pno."
      } <<
        \new Staff \new Voice = "pianoUpperVoice" \pianoUpperMusic
        \new Staff \new Voice = "pianoLowerVoice" \pianoLowerMusic
      >>
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}
