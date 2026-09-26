# syntax=docker/dockerfile:1
# Audiveris worker: Stage 2. Runs the pinned Audiveris release (which bundles its
# own Java runtime) behind an RQ worker on the "engine" queue.
# Build context is the repository root.
#   docker build -f services/audiveris-worker/Dockerfile -t lilyscan-audiveris .

FROM ubuntu:24.04
ARG AUDIVERIS_VERSION=5.11.0
# OCR models come from the full "tessdata" repository (Apache-2.0) at a pinned
# commit. Audiveris uses Tesseract's legacy engine, which the LSTM-only
# "tessdata_fast" models (and Ubuntu's tesseract-ocr-* packages) do not include.
# The models listed in tessdata.sha256 are baked in and checksum-verified; other
# languages named in LILYSCAN_OCR_LANGUAGES are fetched at startup (entrypoint.sh).
ARG TESSDATA_COMMIT=ced78752cc61322fb554c280d13360b35b8684e4

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
    TESSDATA_BASE_URL=https://github.com/tesseract-ocr/tessdata/raw/${TESSDATA_COMMIT} \
    TESSDATA_CACHE=/var/cache/lilyscan/tessdata \
    LILYSCAN_OCR_LANGUAGES=eng+lat+deu+fra \
    AUDIVERIS_MAX_HEAP=3G \
    LILYSCAN_DATA_DIR=/data

# The .deb is unpacked rather than installed: its post-install script only does
# desktop integration (menus, MIME types), which fails in a headless container.
# Its declared runtime dependencies are installed explicitly, minus xdg-utils.
# Setting sun.java2d.uiScale in the launcher config skips Audiveris's GTK-based
# HiDPI probe, which otherwise needs libgtk-3 even in batch mode.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl python3.12 fontconfig \
 && curl -fsSL -o /tmp/audiveris.deb \
      "https://github.com/Audiveris/audiveris/releases/download/${AUDIVERIS_VERSION}/Audiveris-${AUDIVERIS_VERSION}-ubuntu24.04-x86_64.deb" \
 && deps="$(dpkg-deb -f /tmp/audiveris.deb Depends \
      | tr ',' '\n' | sed -e 's/([^)]*)//g' -e 's/|.*//' -e 's/[[:space:]]//g' \
      | grep -v -e '^$' -e '^xdg-utils$')" \
 && echo "Audiveris runtime dependencies:" $deps \
 && apt-get install -y --no-install-recommends $deps \
 && dpkg-deb -x /tmp/audiveris.deb / \
 && files="$(dpkg-deb -c /tmp/audiveris.deb | awk '{print $NF}' | sed 's|^\./|/|')" \
 && launcher="$(echo "$files" | grep -E '/bin/Audiveris$' | head -n1)" \
 && cfg="$(echo "$files" | grep -E '/Audiveris\.cfg$' | head -n1)" \
 && test -n "$launcher" && test -n "$cfg" && test -x "$launcher" \
 && grep -q '^java-options=-Xmx' "$cfg" \
 && sed -i '/^java-options=-Xmx/a java-options=-Dsun.java2d.uiScale=1' "$cfg" \
 && ln -s "$launcher" /usr/local/bin/audiveris \
 && ln -s "$cfg" /usr/local/etc/Audiveris.cfg \
 && rm /tmp/audiveris.deb \
 && rm -rf /var/lib/apt/lists/*

COPY services/audiveris-worker/tessdata.sha256 /opt/tessdata/SHA256SUMS
RUN cd "$TESSDATA_PREFIX" \
 && for model in $(awk '{print $2}' SHA256SUMS); do \
      curl -fsSL -o "$model" "$TESSDATA_BASE_URL/$model" || exit 1; \
    done \
 && sha256sum -c SHA256SUMS \
 && mkdir -p "$TESSDATA_CACHE"

COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project --python python3.12 --extra worker
COPY lilyscan ./lilyscan
COPY services/lilyscan_app ./services/lilyscan_app
RUN uv sync --frozen --no-dev --python python3.12 --extra worker
COPY services/audiveris-worker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh && audiveris -batch -version && lilyscan versions

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["python", "-m", "lilyscan_app.worker", "--role", "engine"]
