#!/bin/sh
# Prepare the Audiveris runtime, then run the given command.
set -eu

# --- JVM heap cap ------------------------------------------------------------
# The Audiveris launcher reads its JVM options from Audiveris.cfg, which ships with
# -Xmx8G. Command-line options there override JAVA_TOOL_OPTIONS, so the cap has to
# be written into the launcher config itself.
cfg="${AUDIVERIS_CFG:-/usr/local/etc/Audiveris.cfg}"

if [ -n "${AUDIVERIS_MAX_HEAP:-}" ]; then
  case "$AUDIVERIS_MAX_HEAP" in
    *[!0-9kKmMgG]* | [!0-9]*)
      echo "AUDIVERIS_MAX_HEAP must look like 3G or 3072m, got '$AUDIVERIS_MAX_HEAP'" >&2
      exit 64
      ;;
  esac
  sed -i --follow-symlinks "s/^java-options=-Xmx.*/java-options=-Xmx${AUDIVERIS_MAX_HEAP}/" "$cfg"
fi

# --- OCR languages -----------------------------------------------------------
# Every language in LILYSCAN_OCR_LANGUAGES (e.g. eng+lat+deu+fra+ita) needs a
# model in $TESSDATA_PREFIX. Built-in models are baked into the image. Others are
# downloaded once, from the same pinned tessdata commit, into $TESSDATA_CACHE
# (mount a volume there to keep them). Any other *.traineddata placed in the
# cache, such as a custom model, is made available too. Built-in models win on a
# name clash.
if [ -n "${TESSDATA_PREFIX:-}" ] && [ -n "${TESSDATA_CACHE:-}" ]; then
  mkdir -p "$TESSDATA_CACHE"
  for lang in $(echo "${LILYSCAN_OCR_LANGUAGES:-}" | tr '+,' '  '); do
    case "$lang" in
      *[!a-z_]* | '')
        echo "invalid OCR language '$lang' in LILYSCAN_OCR_LANGUAGES" >&2
        exit 64
        ;;
    esac
    [ -e "$TESSDATA_PREFIX/$lang.traineddata" ] && continue
    [ -s "$TESSDATA_CACHE/$lang.traineddata" ] && continue
    echo "Downloading OCR model '$lang'" >&2
    if curl -fsSL -o "$TESSDATA_CACHE/$lang.traineddata.part" \
        "$TESSDATA_BASE_URL/$lang.traineddata"; then
      mv "$TESSDATA_CACHE/$lang.traineddata.part" "$TESSDATA_CACHE/$lang.traineddata"
    else
      rm -f "$TESSDATA_CACHE/$lang.traineddata.part"
      echo "warning: could not download OCR model '$lang'; jobs that need it will fail" >&2
    fi
  done
  for model in "$TESSDATA_CACHE"/*.traineddata; do
    [ -e "$model" ] || continue
    name="$(basename "$model")"
    [ -e "$TESSDATA_PREFIX/$name" ] || ln -s "$model" "$TESSDATA_PREFIX/$name"
  done
fi

exec "$@"
