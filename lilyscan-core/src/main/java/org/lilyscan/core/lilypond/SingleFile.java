package org.lilyscan.core.lilypond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.util.Py;

/**
 * A LilyPond project as one file (port of {@code lilyscan/lilypond/single.py}): the header,
 * every part's variables, then a {@code \book} for the score and one for each part.
 */
public final class SingleFile {
    private SingleFile() {}

    private static final int FLAGS = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern INCLUDE =
            Pattern.compile("^[ \\t]*\\\\include[ \\t]+\"([^\"]+)\"[ \\t]*\\n?", FLAGS | Pattern.MULTILINE);
    private static final Pattern VERSION =
            Pattern.compile("^[ \\t]*\\\\version[ \\t]+\"[^\"]*\"[ \\t]*\\n?", FLAGS | Pattern.MULTILINE);
    private static final Pattern HEADER =
            Pattern.compile("^\\\\header \\{\\n.*?^\\}\\n", FLAGS | Pattern.MULTILINE | Pattern.DOTALL);
    private static final String STRING = "\"(?:[^\"\\\\]|\\\\.)*\"";
    private static final Pattern STRING_RE = Pattern.compile(STRING, FLAGS);
    private static final Pattern INSTRUMENT = Pattern.compile(
            "\\binstrumentName = (" + STRING + "|\\\\markup \\\\center-column \\{ (?:" + STRING + " )+\\})", FLAGS);
    private static final Pattern PART_LAYOUT = Pattern.compile("^layout/part-(.+)\\.ly$", FLAGS);
    private static final Pattern VOICE_MUSIC = Pattern.compile("(\\\\new Voice = \"[^\"]*\" )\\\\([A-Za-z]+)", FLAGS);

    /** A part's layout: consecutive whole-measure rests as one multi-measure rest. */
    public static final String PART_LAYOUT_BLOCK = "\\layout { \\context { \\Score skipBars = ##t } }";

    /** Merges runs of equal whole-measure rests into one ({@code R1 | R1 |} to {@code R1*2 |}). */
    public static final String MERGE_RESTS = """
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
    (ly:music-deep-copy music)))""";

    /** A part's {@code \score} as players read it (multi-measure rests), for a one-staff part. */
    public static String partScore(String layout) {
        String score = layout.replaceFirst(Pattern.quote("\\layout { }"), Matcher.quoteReplacement(PART_LAYOUT_BLOCK));
        if (!score.contains("PianoStaff")) {
            score = VOICE_MUSIC.matcher(score).replaceAll("$1\\\\mergeFullBarRests \\\\$2");
        }
        return score;
    }

    private static String stripNewlines(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '\n') a++;
        while (b > a && s.charAt(b - 1) == '\n') b--;
        return s.substring(a, b);
    }

    /** A project file without its {@code \version} and {@code \include} lines. */
    static String body(String text) {
        return stripNewlines(INCLUDE.matcher(VERSION.matcher(text).replaceFirst("")).replaceAll(""));
    }

    private static String indented(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) out.add(line.isEmpty() ? line : "  " + line);
        return String.join("\n", out);
    }

    /** A {@code \book} holding one score, written to {@code <file>-<suffix>.pdf}. */
    public static String book(String suffix, String score, String header) {
        List<String> lines = new ArrayList<>(List.of("\\book {", "  \\bookOutputSuffix \"" + suffix + "\""));
        if (header != null && !header.isEmpty()) lines.add("  \\header { " + header + " }");
        lines.add(indented(score));
        lines.add("}");
        return String.join("\n", lines);
    }

    private static String oneLine(String name) {
        if (!name.startsWith("\\markup")) return name;
        List<String> parts = new ArrayList<>();
        Matcher m = STRING_RE.matcher(name);
        while (m.find()) {
            String s = m.group();
            parts.add(s.substring(1, s.length() - 1));
        }
        return "\"" + String.join(" ", parts) + "\"";
    }

    /** The project in {@code files} (relative path to text, as generated) as one file. */
    public static String singleFile(Map<String, String> files) {
        String main = files.get("main.ly");
        Matcher version = VERSION.matcher(main);
        Matcher header = HEADER.matcher(main);
        List<String> included = new ArrayList<>();
        Matcher inc = INCLUDE.matcher(main);
        while (inc.find()) included.add(inc.group(1));
        List<String> out = new ArrayList<>(List.of(version.find() ? Py.strip(version.group()) : "", ""));
        if (header.find()) {
            out.add(stripNewlines(header.group()));
            out.add("");
        }
        for (String rel : included) {
            if (!rel.startsWith("layout/")) out.add(body(files.get(rel)) + "\n");
        }
        String score = body(files.get("layout/score.ly"));
        List<String> parts = new ArrayList<>();
        for (String rel : included) {
            if (rel.startsWith("parts/")) {
                String slug = rel.substring("parts/".length(), rel.length() - ".ly".length());
                if (files.containsKey("layout/part-" + slug + ".ly")) parts.add(slug);
            }
        }
        TreeSet<String> extra = new TreeSet<>();
        for (String rel : files.keySet()) {
            Matcher m = PART_LAYOUT.matcher(rel);
            if (m.find() && !parts.contains(m.group(1))) extra.add(m.group(1));
        }
        parts.addAll(extra);
        if (parts.size() < 2) {
            out.add(score);
            out.add("");
            return String.join("\n", out);
        }
        List<String> books = new ArrayList<>();
        books.add(book("score", score, null));
        for (String slug : parts) {
            String layout = files.get("layout/part-" + slug + ".ly");
            Matcher name = INSTRUMENT.matcher(layout);
            String instrument = name.find() ? "instrument = " + oneLine(name.group(1)) : null;
            books.add(book(slug, partScore(body(layout)), instrument));
        }
        out.add(MERGE_RESTS);
        out.add("");
        out.add(String.join("\n\n", books));
        out.add("");
        return String.join("\n", out);
    }
}
