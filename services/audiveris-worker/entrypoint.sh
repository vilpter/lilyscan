#!/bin/sh
# Apply the runtime heap cap, then run the given command.
#
# The Audiveris launcher reads its JVM options from Audiveris.cfg, which ships with
# -Xmx8G. Command-line options there override JAVA_TOOL_OPTIONS, so the cap has to
# be written into the launcher config itself.
set -eu

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

exec "$@"
