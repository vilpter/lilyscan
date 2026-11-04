#!/usr/bin/env bash
# End-to-end check of the server image, as a user runs it: start the container, upload a page,
# wait for the job, then fetch the review and the downloads. Used by CI (java-ci.yml).
#
#   docker/smoke-test.sh lilyscan:ci
set -euo pipefail
image="${1:?usage: smoke-test.sh IMAGE}"
port="${LILYSCAN_SMOKE_PORT:-8765}"
pdf="$(dirname "$0")/../docs/upstream/audiveris/repro/satb-01.pdf"
name="lilyscan-smoke-$$"
base="http://127.0.0.1:${port}"

docker run -d --name "$name" -p "${port}:8000" "$image" >/dev/null
trap 'docker logs "$name" | tail -50; docker rm -f "$name" >/dev/null' EXIT

for _ in $(seq 1 60); do curl -sf "$base/api/health" >/dev/null && break; sleep 1; done
curl -sf "$base/api/health"; echo
curl -sf "$base/" | grep -q "app.js"

job=$(curl -sf -F "files=@${pdf}" "$base/api/jobs" | jq -r .id)
echo "job $job"
status=""
for _ in $(seq 1 120); do
  status=$(curl -sf "$base/api/jobs/$job" | jq -r .status)
  [ "$status" = done ] || [ "$status" = failed ] && break
  sleep 5
done
report=$(curl -sf "$base/api/jobs/$job")
if [ "$(echo "$report" | jq -r .status)" != done ]; then
  echo "job failed: $(echo "$report" | jq -r .error)" >&2
  exit 1
fi
echo "$report" | jq -r '.report | "located \(.geometry.located_events) of \(.geometry.events) events; QA \([.qa.checks[] | "\(.id)=\(.passed)"] | join(" "))"'
echo "$report" | jq -e '.report.geometry.located_events > 0 and .report.qa.checks[0].passed' >/dev/null \
  || { echo "nothing located, or LilyPond failed" >&2; exit 1; }
curl -sf "$base/api/jobs/$job/review" | jq -e '.measures | length > 0' >/dev/null
for kind in ly ly-project pdf midi musicxml omr; do
  curl -sf -o /dev/null "$base/api/jobs/$job/download/$kind"
done
curl -sf -o /dev/null "$base/api/jobs/$job/pages/1.png"
echo "smoke test passed"
