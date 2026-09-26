# syntax=docker/dockerfile:1
# Pipeline worker: Stages 0-1 and 3-8 (everything except Audiveris).
# Build context is the repository root.
#   docker build -f services/worker/Dockerfile --build-arg VARIANT=cpu  -t lilyscan-worker:cpu  .
#   docker build -f services/worker/Dockerfile --build-arg VARIANT=cuda -t lilyscan-worker:cuda .

ARG LILYPOND_VERSION=2.26.0

FROM ubuntu:24.04 AS lilypond
ARG LILYPOND_VERSION
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/lilypond.tar.gz \
      "https://gitlab.com/lilypond/lilypond/-/releases/v${LILYPOND_VERSION}/downloads/lilypond-${LILYPOND_VERSION}-linux-x86_64.tar.gz" \
 && tar -xzf /tmp/lilypond.tar.gz -C /opt \
 && mv "/opt/lilypond-${LILYPOND_VERSION}" /opt/lilypond \
 && rm /tmp/lilypond.tar.gz

FROM ubuntu:24.04
ARG VARIANT=cpu
LABEL org.opencontainers.image.source=https://github.com/vilpter/lilyscan \
      org.opencontainers.image.description="Lilyscan pipeline worker" \
      org.opencontainers.image.licenses=AGPL-3.0-or-later
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:/opt/lilypond/bin:$PATH \
    LILYPOND_BIN=/opt/lilypond/bin/lilypond \
    LILYSCAN_DATA_DIR=/data

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3.12 ca-certificates fontconfig libgl1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv
COPY --from=lilypond /opt/lilypond /opt/lilypond

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project --python python3.12 \
      --extra worker --extra musicxml --extra ml
COPY lilyscan ./lilyscan
COPY services/lilyscan_app ./services/lilyscan_app
RUN uv sync --frozen --no-dev --python python3.12 --extra worker --extra musicxml --extra ml

# CUDA variant: swap in GPU inference wheels. The wheels bundle the CUDA runtime;
# the host needs only the NVIDIA driver and the NVIDIA Container Toolkit.
RUN if [ "$VARIANT" = "cuda" ]; then \
      uv pip uninstall --python /app/.venv/bin/python onnxruntime \
   && uv pip install --python /app/.venv/bin/python onnxruntime-gpu \
   && uv pip install --python /app/.venv/bin/python \
        --index-url https://download.pytorch.org/whl/cu128 torch ; \
    elif [ "$VARIANT" != "cpu" ]; then echo "VARIANT must be cpu or cuda" >&2; exit 1; fi

RUN lilypond --version | head -n1 && lilyscan selftest

CMD ["python", "-m", "lilyscan_app.worker", "--role", "pipeline"]
