# syntax=docker/dockerfile:1
# HTTP API. Build context is the repository root.

FROM ubuntu:24.04
LABEL org.opencontainers.image.source=https://github.com/vilpter/lilyscan \
      org.opencontainers.image.description="Lilyscan HTTP API" \
      org.opencontainers.image.licenses=AGPL-3.0-or-later
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    LILYSCAN_DATA_DIR=/data

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3.12 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project --python python3.12 --extra api
COPY lilyscan ./lilyscan
COPY services/lilyscan_app ./services/lilyscan_app
RUN uv sync --frozen --no-dev --python python3.12 --extra api

EXPOSE 8000
CMD ["uvicorn", "lilyscan_app.api:app", "--host", "0.0.0.0", "--port", "8000"]
