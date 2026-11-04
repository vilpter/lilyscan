\version "2.26.0"

\header {
  title = "Lilyscan quartet-01"
  composer = "Generated"
  tagline = ##f
}

violinIMusic = {
  % m. 1
  \clef treble \key e \major \numericTimeSignature \time 4/4 r8 cis''16 r16 a'4 gis'8 r8 a'16 b'16 cis''16 d''16 |
  % m. 2
  fis''2 e''4 fis''8 b''16~ b''16 |
  % m. 3
  cis'''8 b''8 a''8 gis''8 ais''16 fis''16 b'8 cis''4 |
  % m. 4
  \tuplet 3/2 4 { r8 a''8~ a''8 } gis''4 e''8.~ e''16 fis''8 a'8 |
  % m. 5
  cis''8 b'8 gis'16~ gis'16 b'16~ b'16 a'16 gis'16 fis'16 d'16~ d'8 cis'16 a16 |
  % m. 6
  b2 cis'2 |
  % m. 7
  dis'8. b'16 cis''8. dis''16~ dis''2 |
  % m. 8
  fis''2 as'8 cis''8 \tuplet 3/2 4 { dis''8 b'8 a'8 } | \bar "|."
}

violinIIMusic = {
  % m. 1
  \clef treble \key e \major \numericTimeSignature \time 4/4 b''8. gis''16 cis''16 b'16 r16 gis'16 \tuplet 3/2 4 { a'8 b'8 e'8~ } e'16~ e'16 fis'8 |
  % m. 2
  e'4 b'4. a'8 fisis'8 r8 |
  % m. 3
  gis'4 a'4 \tuplet 3/2 4 { fis'8 gis'8 b'8 } cis''16 gis''16 a''16 g''16 |
  % m. 4
  a''16 fis''16 gis''8 fis''4 e''8 c''16 e''16 gis'16 r16 fis'8~ |
  % m. 5
  fis'8 b'8 e''8 dis''16 fis''16 r4 e''8 fis''16 r16 |
  % m. 6
  e''4 cis''4 dis''8. a'16 gis'16 a'16 dis''16 eis''16 |
  % m. 7
  fis''8 gis''16 b''16 r16 gis''16~ gis''16 fis''16 a'4. b'8 |
  % m. 8
  cis''8 b'16 a'16 e'2 cis'16 b16 a8 | \bar "|."
}

violaMusic = {
  % m. 1
  \clef alto \key e \major \numericTimeSignature \time 4/4 cis'8 e'16 a'16 fis'8 r16 gis'16 a'16 b'16 a'16 cis''16 gis'4 |
  % m. 2
  a'4. gis'8 fis'8. gis'16 a'8 b'8 |
  % m. 3
  fis'4 a'16 b'16 c''16 r16 dis''16 b'16 a'8 cis''8 a'8 |
  % m. 4
  b'16 cis''16 e'16 gisis'16 fis'4. gis'8 fis'4 |
  % m. 5
  e'4 fis'4 gis'8. a'16 c''4 |
  % m. 6
  a'16 fis'16 b8 cis'2 b8 a8 |
  % m. 7
  r8 cis'16 gis16 a16 cis'16~ cis'8 fis8 gis16 r16 a16 b16 cis'16 dis'16 |
  % m. 8
  f'2 dis''8 b'16~ b'16 cis''8 e'16 gis'16 | \bar "|."
}

violoncelloMusic = {
  % m. 1
  \clef bass \key e \major \numericTimeSignature \time 4/4 gis,4. e8 fis8 dis8 \tuplet 3/2 4 { r8 r8 b,8 } |
  % m. 2
  \tuplet 3/2 4 { cis8 dis8 a,8 } fis8 e8 fis4. gis8 |
  % m. 3
  a16 cis'16 r8 dis'16 g16 a8 gis16 b16~ b16 a16 b4 |
  % m. 4
  dis'8 e'8 gis8 dis16 b16 dis'16 b16 cis'8 \tuplet 3/2 4 { gis8~ gis8 a8 } |
  % m. 5
  b16 g16 b8 cis'16~ cis'16 fis8 gis16 eis16 fis16~ fis16 cis'4 |
  % m. 6
  b8. gis16 r16 a16~ a16 fis16~ fis4 gis8 a16 dis16~ |
  % m. 7
  dis16 fis16 as8~ as4. gis8 r4 |
  % m. 8
  a16 cis'16 f16 gis16 a16 b16 gis16 b16~ b16 a16 gis8 \tuplet 3/2 4 { a8 cis'8 e'8 } | \bar "|."
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
        \new Staff \with { instrumentName = "Violin I" shortInstrumentName = "Vln. I" } \new Voice = "violinIVoice" \violinIMusic
        \new Staff \with { instrumentName = "Violin II" shortInstrumentName = "Vln. II" } \new Voice = "violinIIVoice" \violinIIMusic
        \new Staff \with { instrumentName = "Viola" shortInstrumentName = "Vla." } \new Voice = "violaVoice" \violaMusic
        \new Staff \with { instrumentName = "Violoncello" shortInstrumentName = "Vc." } \new Voice = "violoncelloVoice" \violoncelloMusic
      >>
    >>
    \layout { }
    \midi { }
  }
}

\book {
  \bookOutputSuffix "violin-i"
  \header { instrument = "Violin I" }
  \score {
    <<
      \new Staff \with { instrumentName = "Violin I" shortInstrumentName = "Vln. I" } \new Voice = "violinIVoice" \mergeFullBarRests \violinIMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "violin-ii"
  \header { instrument = "Violin II" }
  \score {
    <<
      \new Staff \with { instrumentName = "Violin II" shortInstrumentName = "Vln. II" } \new Voice = "violinIIVoice" \mergeFullBarRests \violinIIMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "viola"
  \header { instrument = "Viola" }
  \score {
    <<
      \new Staff \with { instrumentName = "Viola" shortInstrumentName = "Vla." } \new Voice = "violaVoice" \mergeFullBarRests \violaMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "violoncello"
  \header { instrument = "Violoncello" }
  \score {
    <<
      \new Staff \with { instrumentName = "Violoncello" shortInstrumentName = "Vc." } \new Voice = "violoncelloVoice" \mergeFullBarRests \violoncelloMusic
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}
