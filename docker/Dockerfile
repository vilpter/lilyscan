# Lilyscan server image (design-java.md D22, §7) — the primary delivery.
# One image, one service: the JVM, Audiveris (bundled in the fat jar, with its JavaCPP native
# OCR), LilyPond and the OCR models, so `docker compose up` needs nothing on the host but Docker.
#
# The fat jar is built by CI (it needs the Audiveris submodule + a UTF-8 locale + JDK 25) and
# copied in here; CI publishes the image to GHCR so users pull rather than build. To build
# locally, run first:
#   LANG=C.UTF-8 ./gradlew :lilyscan-cli:shadowJar   # requires: git submodule update --init
#   docker build -f docker/Dockerfile -t lilyscan .
FROM eclipse-temurin:25-jre

# LilyPond 2.26.0 (D5) and the pinned Tesseract OCR models (D10), both fetched at build time (not
# committed — model binaries are large and are not redistributed in the repo, D11). The full
# tessdata models with the legacy engine are required, or Audiveris skips text silently (design.md
# §5). LilyPond is launched as a subprocess; the models are read by Audiveris via TESSDATA_PREFIX.
ARG LILYPOND_VERSION=2.26.0
# The tessdata commit and checksums are the Python audiveris-worker's pin (D10); the models are
# verified against docker/tessdata.sha256.
ARG TESSDATA_COMMIT=ced78752cc61322fb554c280d13360b35b8684e4
ENV TESSDATA_PREFIX=/opt/tessdata
# Models for other languages are downloaded at startup into this cache (docker/entrypoint.sh);
# curl stays installed for that.
ENV TESSDATA_CACHE=/var/cache/lilyscan/tessdata \
    TESSDATA_BASE_URL=https://raw.githubusercontent.com/tesseract-ocr/tessdata/${TESSDATA_COMMIT}
COPY docker/tessdata.sha256 /opt/tessdata/SHA256SUMS
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates fontconfig; \
    curl -fLo /tmp/lilypond.tar.gz \
      "https://gitlab.com/lilypond/lilypond/-/releases/v${LILYPOND_VERSION}/downloads/lilypond-${LILYPOND_VERSION}-linux-x86_64.tar.gz"; \
    tar -xzf /tmp/lilypond.tar.gz -C /opt; \
    ln -sf "/opt/lilypond-${LILYPOND_VERSION}/bin/lilypond" /usr/local/bin/lilypond; \
    rm -f /tmp/lilypond.tar.gz; \
    cd "${TESSDATA_PREFIX}"; \
    for lang in eng lat deu fra; do \
      curl -fsSL -o "${lang}.traineddata" \
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata/${TESSDATA_COMMIT}/${lang}.traineddata"; \
    done; \
    sha256sum -c SHA256SUMS; \
    cd /; \
    rm -rf /var/lib/apt/lists/*; \
    lilypond --version

COPY lilyscan-cli/build/libs/lilyscan.jar /app/lilyscan.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

ENV LILYSCAN_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8000

ENTRYPOINT ["/app/entrypoint.sh", "java", "-jar", "/app/lilyscan.jar", "serve", "--port", "8000", "--data", "/data"]
