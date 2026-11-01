\version "2.26.0"

\header {
  composer = "Generated"
  tagline = ##f
}

fOneMusic = {
  % m. 1
  \clef treble \key bes \major \numericTimeSignature \time 4/4 R1 |
  % m. 2
  R1 |
  % m. 3
  R1 |
  % m. 4
  R1 |
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

fluteMusic = {
  % m. 1
  \clef treble \key bes \major \numericTimeSignature \time 4/4 bes'4 a'4. g'8 c''16 r16 bes'8 |
  % m. 2
  a'16 g'16~ g'16 es'16~ es'4 f'4.~ f'8 |
  % m. 3
  r4 g'4~ g'4 f'4~ |
  % m. 4
  f'8. es'16 f'2 d'16 es'16 d'8 |
  % m. 5
  R1 |
  % m. 6
  R1 |
  % m. 7
  R1 |
  % m. 8
  R1 |
  % m. 9
  R1 |
  % m. 10
  R1 |
  % m. 11
  R1 |
  % m. 12
  R1 |
  % m. 13
  R1 |
  % m. 14
  R1 |
  % m. 15
  R1 |
  % m. 16
  R1 |
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
      \new StaffGroup <<
        \new Staff \with { instrumentName = "F1." shortInstrumentName = "F1." } \new Voice = "fOneVoice" \fOneMusic
        \new Staff \with { instrumentName = "Flute" shortInstrumentName = "Flute" } \new Voice = "fluteVoice" \fluteMusic
      >>
    >>
    \layout { }
    \midi { }
  }
}

\book {
  \bookOutputSuffix "f1"
  \header { instrument = "F1." }
  \score {
    <<
      \new Staff \with { instrumentName = "F1." shortInstrumentName = "F1." } \new Voice = "fOneVoice" \mergeFullBarRests \fOneMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "flute"
  \header { instrument = "Flute" }
  \score {
    <<
      \new Staff \with { instrumentName = "Flute" shortInstrumentName = "Flute" } \new Voice = "fluteVoice" \mergeFullBarRests \fluteMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}
