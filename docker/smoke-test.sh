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

job=$(curl -sf -F "files=@${pdf}" "$base/api/jobs" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "job $job"
status=""
for _ in $(seq 1 120); do
  status=$(curl -sf "$base/api/jobs/$job" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  [ "$status" = done ] || [ "$status" = failed ] && break
  sleep 5
done
curl -sf "$base/api/jobs/$job" | python3 -c '
import json, sys
job = json.load(sys.stdin)
assert job["status"] == "done", job.get("error")
r = job["report"]
g = r["geometry"]
print("located", g["located_events"], "of", g["events"], "events; QA", [(c["id"], c["passed"]) for c in r["qa"]["checks"]])
assert g["located_events"] > 0 and r["qa"]["checks"][0]["passed"], "nothing located, or LilyPond failed"
'
curl -sf "$base/api/jobs/$job/review" | python3 -c 'import json,sys; assert json.load(sys.stdin)["measures"]'
for kind in ly ly-project pdf midi musicxml omr; do
  curl -sf -o /dev/null "$base/api/jobs/$job/download/$kind"
done
curl -sf -o /dev/null "$base/api/jobs/$job/pages/1.png"
echo "smoke test passed"
