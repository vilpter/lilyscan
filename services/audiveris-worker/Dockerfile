# syntax=docker/dockerfile:1
# Audiveris worker: Stage 2. Runs the pinned Audiveris release (which bundles its
# own Java runtime) behind an RQ worker on the "engine" queue.
# Build context is the repository root.
#   docker build -f services/audiveris-worker/Dockerfile -t lilyscan-audiveris .

FROM ubuntu:24.04
ARG AUDIVERIS_VERSION=5.11.0
# Full "tessdata" English model (Apache-2.0). Audiveris uses Tesseract's legacy
# engine, which the LSTM-only "tessdata_fast" models (and Ubuntu's
# tesseract-ocr-* packages) do not include.
ARG TESSDATA_ENG_URL=https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
ARG TESSDATA_ENG_SHA256=daa0c97d651c19fba3b25e81317cd697e9908c8208090c94c3905381c23fc047

LABEL org.opencontainers.image.source=https://github.com/vilpter/lilyscan \
      org.opencontainers.image.description="Lilyscan Audiveris engine worker" \
      org.opencontainers.image.licenses=AGPL-3.0-or-later

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    AUDIVERIS_BIN=/usr/local/bin/audiveris \
    TESSDATA_PREFIX=/opt/tessdata \
    AUDIVERIS_MAX_HEAP=3G \
    LILYSCAN_DATA_DIR=/data

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl python3.12 fontconfig \
 && curl -fsSL -o /tmp/audiveris.deb \
      "https://github.com/Audiveris/audiveris/releases/download/${AUDIVERIS_VERSION}/Audiveris-${AUDIVERIS_VERSION}-ubuntu24.04-x86_64.deb" \
 && apt-get install -y --no-install-recommends /tmp/audiveris.deb \
 && pkg="$(dpkg-deb -f /tmp/audiveris.deb Package)" \
 && launcher="$(dpkg -L "$pkg" | grep -E '/bin/Audiveris$' | head -n1)" \
 && cfg="$(dpkg -L "$pkg" | grep -E '/Audiveris\.cfg$' | head -n1)" \
 && test -n "$launcher" && test -n "$cfg" \
 && grep -q '^java-options=-Xmx' "$cfg" \
 && ln -s "$launcher" /usr/local/bin/audiveris \
 && ln -s "$cfg" /usr/local/etc/Audiveris.cfg \
 && mkdir -p "$TESSDATA_PREFIX" \
 && curl -fsSL -o "$TESSDATA_PREFIX/eng.traineddata" "$TESSDATA_ENG_URL" \
 && echo "$TESSDATA_ENG_SHA256  $TESSDATA_PREFIX/eng.traineddata" | sha256sum -c - \
 && rm /tmp/audiveris.deb \
 && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project --python python3.12 --extra worker
COPY lilyscan ./lilyscan
COPY services/lilyscan_app ./services/lilyscan_app
RUN uv sync --frozen --no-dev --python python3.12 --extra worker
COPY services/audiveris-worker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh && lilyscan versions

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["python", "-m", "lilyscan_app.worker", "--role", "engine"]
