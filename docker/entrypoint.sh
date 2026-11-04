#!/bin/sh
# Prepare the OCR models, then run the given command (the Lilyscan server).
#
# Every language in LILYSCAN_OCR_LANGUAGES (e.g. eng+lat+deu+fra+ita) needs a model in
# $TESSDATA_PREFIX. eng, lat, deu and fra are baked into the image. Others are downloaded
# once, from the same pinned tessdata commit, into $TESSDATA_CACHE (a volume keeps them).
# Any other *.traineddata placed in the cache, such as a custom model, is made available
# too. Built-in models win on a name clash.
set -eu

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
    if curl -fsSL -o "$TESSDATA_CACHE/$lang.traineddata.part" "$TESSDATA_BASE_URL/$lang.traineddata"; then
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
