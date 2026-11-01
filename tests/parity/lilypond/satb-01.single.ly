\version "2.26.0"

\header {
  title = "Lilyscan satb-01"
  composer = "Generated"
  tagline = ##f
}

sopranoMusic = {
  % m. 1
  \clef treble \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { a'8 b'8 g'8 } b'8~ b'8 a'2 |
  % m. 2
  g'16 e'16 g'8 fis'4 d'2 |
  % m. 3
  \tuplet 3/2 4 { fis'8 b'8 d''8 } c''2 b'16 c''16 b'16 c''16~ |
  % m. 4
  c''4 d''8 b'16 c''16 b'8 g'16 fis'16 g'8 b'8 |
  % m. 5
  fis''4. g''8 fis''8 e''16 g''16 fisis''8. g''16 |
  % m. 6
  fis''4 d''2 e''8 fisis''8 |
  % m. 7
  d''4 b'4. d''8~ d''8 e''8 |
  % m. 8
  fis''4 \tuplet 3/2 4 { e''8 fis''8 e''8 } g''2 | \bar "|."
}

sopranoVerseOne = \lyricmode {
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis Ag --
  nus De -- i qui
  tol -- lis pec -- ca -- ta
}

altoMusic = {
  % m. 1
  \clef treble \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { b'8 g'8 c''8 } g'8 e'8 d'2 |
  % m. 2
  c'16 e'16 c'8 b4 d'2 |
  % m. 3
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

altoVerseOne = \lyricmode {
  Ag -- nus De -- i qui tol --
  lis pec -- ca -- ta mun --
  di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec -- ca --
  ta mun -- di mi -- se -- re -- re
  no -- bis Ag -- nus
  De -- i qui tol -- lis
  pec -- ca -- ta
}

tenorMusic = {
  % m. 1
  \clef "treble_8" \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { as8 g8~ g8 } e8 fis8 d2 |
  % m. 2
  fis16 d16 c8 d4 e2 |
  % m. 3
  \tuplet 3/2 4 { d8 e8 fis8 } e2 b16 fis16 c'16 e'16 |
  % m. 4
  fis'4 e'8 d'16 c'16~ c'8 a16 d'16 b8 c'8 |
  % m. 5
  e'4. c'8 b8 d16 e16 g8. a16 |
  % m. 6
  b4 d'2~ d'8 e'8 |
  % m. 7
  fis'4 e'4. fis'8 e'8 a8 |
  % m. 8
  c'4 \tuplet 3/2 4 { fis8 a8 fis'8 } e'2 | \bar "|."
}

tenorVerseOne = \lyricmode {
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis
  Ag -- nus De -- i qui
  tol -- lis pec -- ca -- ta
}

bassMusic = {
  % m. 1
  \clef bass \key e \minor \numericTimeSignature \time 4/4 \tuplet 3/2 4 { b,8 d8 fis,8 } c8 d8 fis2 |
  % m. 2
  a16 d16 a8~ a4 c'2 |
  % m. 3
  \tuplet 3/2 4 { e8 fisis8 a8 } g2 e16 g16 fis16 b,16 |
  % m. 4
  c4 b,8 a,16 b,16 c8 d16 e16~ e8 fis8 |
  % m. 5
  e4. g8 b8 g16 f16 e8. fis16 |
  % m. 6
  es4 d2 e8 c8 |
  % m. 7
  g4 e4. d8 ces8 b,8 |
  % m. 8
  c4 \tuplet 3/2 4 { fis8 e8 b8 } g2 | \bar "|."
}

bassVerseOne = \lyricmode {
  Ag -- nus De -- i qui tol --
  lis pec -- ca -- ta
  mun -- di mi -- se -- re -- re no -- bis
  Ag -- nus De -- i qui tol -- lis pec --
  ca -- ta mun -- di mi -- se -- re --
  re no -- bis Ag --
  nus De -- i qui tol --
  lis pec -- ca -- ta
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
      \new ChoirStaff <<
        \new Staff \with { instrumentName = "Soprano" shortInstrumentName = "S." } \new Voice = "sopranoVoice" \sopranoMusic
        \new Lyrics \lyricsto "sopranoVoice" \sopranoVerseOne
        \new Staff \with { instrumentName = "Alto" shortInstrumentName = "A." } \new Voice = "altoVoice" \altoMusic
        \new Lyrics \lyricsto "altoVoice" \altoVerseOne
        \new Staff \with { instrumentName = "Tenor" shortInstrumentName = "T." } \new Voice = "tenorVoice" \tenorMusic
        \new Lyrics \lyricsto "tenorVoice" \tenorVerseOne
        \new Staff \with { instrumentName = "Bass" shortInstrumentName = "B." } \new Voice = "bassVoice" \bassMusic
        \new Lyrics \lyricsto "bassVoice" \bassVerseOne
      >>
    >>
    \layout { }
    \midi { }
  }
}

\book {
  \bookOutputSuffix "soprano"
  \header { instrument = "Soprano" }
  \score {
    <<
      \new Staff \with { instrumentName = "Soprano" shortInstrumentName = "S." } \new Voice = "sopranoVoice" \mergeFullBarRests \sopranoMusic
      \new Lyrics \lyricsto "sopranoVoice" \sopranoVerseOne
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "alto"
  \header { instrument = "Alto" }
  \score {
    <<
      \new Staff \with { instrumentName = "Alto" shortInstrumentName = "A." } \new Voice = "altoVoice" \mergeFullBarRests \altoMusic
      \new Lyrics \lyricsto "altoVoice" \altoVerseOne
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "tenor"
  \header { instrument = "Tenor" }
  \score {
    <<
      \new Staff \with { instrumentName = "Tenor" shortInstrumentName = "T." } \new Voice = "tenorVoice" \mergeFullBarRests \tenorMusic
      \new Lyrics \lyricsto "tenorVoice" \tenorVerseOne
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}

\book {
  \bookOutputSuffix "bass"
  \header { instrument = "Bass" }
  \score {
    <<
      \new Staff \with { instrumentName = "Bass" shortInstrumentName = "B." } \new Voice = "bassVoice" \mergeFullBarRests \bassMusic
      \new Lyrics \lyricsto "bassVoice" \bassVerseOne
    >>
    \layout { \context { \Score skipBars = ##t } }
  }
}
