#!/bin/sh
# Linux installers for the Lilyscan desktop app (design-java.md D22, JM10): a .deb or .rpm, or a
# plain app image, that installs a working app with nothing else needed.
#
#   packaging/linux/package.sh deb|rpm|app-image [OUT_DIR]
#
# Needs a JDK 25 (jlink and jpackage), the fat jar (./gradlew :lilyscan-cli:shadowJar), curl,
# and dpkg-deb (deb) or rpmbuild (rpm). The package carries:
#   - a Java 25 runtime made by jlink, with only the modules Lilyscan uses;
#   - lilyscan.jar (the app, the server it embeds, Audiveris and its native OCR);
#   - LilyPond, the official build at the version the Docker image pins (D5);
#   - the Tesseract OCR models for English, Latin, German and French, from the tessdata commit
#     the Docker image pins, checked against docker/tessdata.sha256 (D10).
# The app finds the bundled LilyPond and models itself; LILYPOND_BIN and TESSDATA_PREFIX still
# override them. Downloads are cached in build/packaging/cache.
set -eu

type="${1:?usage: package.sh deb|rpm|app-image [OUT_DIR]}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="${2:-$root/build/packaging/out}"
work="$root/build/packaging"
cache="$work/cache"
jar="$root/lilyscan-cli/build/libs/lilyscan.jar"

lilypond_version="$(sed -n 's/^ARG LILYPOND_VERSION=//p' "$root/docker/Dockerfile")"
tessdata_commit="$(sed -n 's/^ARG TESSDATA_COMMIT=//p' "$root/docker/Dockerfile")"
# The app version: the Gradle project's, without -SNAPSHOT (packages need plain numbers).
version="$(sed -n "s/^version *= *'\([0-9.]*\).*/\1/p" "$root/build.gradle" | head -n 1)"
version="${version:-0.1.0}"

jbin="${JAVA_HOME:+$JAVA_HOME/bin/}"
[ -f "$jar" ] || { echo "no $jar: run ./gradlew :lilyscan-cli:shadowJar first" >&2; exit 1; }

# JDK modules: what jdeps finds in the jar, and those loaded as services that it cannot see
# (zip file systems for .omr books, extra charsets, locale data).
modules="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming"
modules="$modules,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml"
modules="$modules,java.xml.crypto,jdk.attach,jdk.charsets,jdk.jdi,jdk.localedata,jdk.unsupported,jdk.zipfs"

mkdir -p "$cache" "$out"
rm -rf "$work/runtime" "$work/input" "$work/content"
mkdir -p "$work/input" "$work/content"

echo "== runtime (jlink)"
"${jbin}jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
  --compress=zip-6 --output "$work/runtime"

echo "== LilyPond $lilypond_version"
ly_tar="$cache/lilypond-$lilypond_version-linux-x86_64.tar.gz"
[ -s "$ly_tar" ] || curl -fL -o "$ly_tar" \
  "https://gitlab.com/lilypond/lilypond/-/releases/v$lilypond_version/downloads/lilypond-$lilypond_version-linux-x86_64.tar.gz"
tar -xzf "$ly_tar" -C "$work/content"
mv "$work/content/lilypond-$lilypond_version" "$work/content/lilypond"
# Guile's Emacs Lisp front end, which LilyPond never uses, has folders named "runtime"; an
# installed jpackage launcher looks up its Java runtime among the package's files by that name.
rm -rf "$work/content/lilypond/share/guile/3.0/language/elisp" \
  "$work/content/lilypond/lib/guile/3.0/ccache/language/elisp"
if find "$work/content" -type d -name runtime | grep -q .; then
  echo "a folder named runtime in the app content would confuse the launcher" >&2
  exit 1
fi
"$work/content/lilypond/bin/lilypond" --version | head -n 1
# It still engraves (the check compiles a small score).
printf '\\version "%s"\n{ c'"'"'4 d'"'"' e'"'"' f'"'"' }\n' "$lilypond_version" > "$work/check.ly"
(cd "$work" && "$work/content/lilypond/bin/lilypond" -s -o check check.ly) && [ -s "$work/check.pdf" ]

echo "== OCR models (tessdata $tessdata_commit)"
mkdir -p "$work/content/tessdata"
for lang in eng lat deu fra; do
  f="$cache/$tessdata_commit-$lang.traineddata"
  [ -s "$f" ] || curl -fsSL -o "$f" \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata/$tessdata_commit/$lang.traineddata"
  cp "$f" "$work/content/tessdata/$lang.traineddata"
done
(cd "$work/content/tessdata" && sha256sum -c "$root/docker/tessdata.sha256")

cp "$jar" "$work/input/lilyscan.jar"

echo "== jpackage ($type)"
set -- --type "$type" --dest "$out" \
  --name Lilyscan --app-version "$version" \
  --vendor "Lilyscan" --copyright "AGPL-3.0-or-later" \
  --description "Sheet music in, editable LilyPond out: transcribe scans with Audiveris and review them." \
  --input "$work/input" --main-jar lilyscan.jar --main-class org.lilyscan.cli.Main \
  --arguments desktop \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Dsun.java2d.uiScale.enabled=true" \
  --runtime-image "$work/runtime" \
  --app-content "$work/content/lilypond" --app-content "$work/content/tessdata" \
  --icon "$root/packaging/linux/lilyscan.png"
if [ "$type" != app-image ]; then
  set -- "$@" --license-file "$root/LICENSE" --resource-dir "$root/packaging/linux/resources" \
    --linux-package-name lilyscan --linux-app-category "Audio" --linux-rpm-license-type "AGPL-3.0-or-later" \
    --linux-menu-group "AudioVideo;Audio;Music;" --linux-shortcut
fi
rm -rf "$out/Lilyscan"
"${jbin}jpackage" "$@"
ls -la "$out"
