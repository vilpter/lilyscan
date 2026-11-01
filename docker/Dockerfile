# Lilyscan server image (design-java.md D22, §7) — the primary delivery.
# One image, one service: the JVM, Audiveris (via the fat jar's JavaCPP natives, wired in JM1),
# LilyPond and the OCR models, so `docker compose up` needs nothing on the host but Docker.
#
# Build the fat jar first:  ./gradlew :lilyscan-cli:shadowJar
# then:                     docker build -f docker/Dockerfile -t lilyscan .

# ---- stage 1: build the fat jar inside the image (reproducible, no host JDK needed) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :lilyscan-cli:shadowJar

# ---- stage 2: the runtime image ----
FROM eclipse-temurin:25-jre

# LilyPond 2.26.0 (D5) and the pinned Tesseract OCR models (D10), both fetched at build time (not
# committed — model binaries are large and are not redistributed in the repo, D11). The full
# tessdata models with the legacy engine are required, or Audiveris skips text silently (design.md
# §5). LilyPond is launched as a subprocess; the models are read by Audiveris via TESSDATA_PREFIX.
ARG LILYPOND_VERSION=2.26.0
# Pin the tessdata commit so the models are reproducible (mirror of the Python audiveris-worker).
ARG TESSDATA_REF=main
ENV TESSDATA_PREFIX=/opt/tessdata
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates fontconfig; \
    curl -fLo /tmp/lilypond.tar.gz \
      "https://gitlab.com/lilypond/lilypond/-/releases/v${LILYPOND_VERSION}/downloads/lilypond-${LILYPOND_VERSION}-linux-x86_64.tar.gz"; \
    tar -xzf /tmp/lilypond.tar.gz -C /opt; \
    ln -sf "/opt/lilypond-${LILYPOND_VERSION}/bin/lilypond" /usr/local/bin/lilypond; \
    rm -f /tmp/lilypond.tar.gz; \
    mkdir -p "${TESSDATA_PREFIX}"; \
    for lang in eng lat deu fra; do \
      curl -fL -o "${TESSDATA_PREFIX}/${lang}.traineddata" \
        "https://github.com/tesseract-ocr/tessdata/raw/${TESSDATA_REF}/${lang}.traineddata"; \
    done; \
    apt-get purge -y curl; apt-get autoremove -y; rm -rf /var/lib/apt/lists/*; \
    lilypond --version

COPY --from=build /src/lilyscan-cli/build/libs/lilyscan.jar /app/lilyscan.jar

ENV LILYSCAN_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/app/lilyscan.jar", "serve", "--port", "8000", "--data", "/data"]
