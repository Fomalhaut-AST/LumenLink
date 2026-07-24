#!/usr/bin/env bash
# Foreground start for debugging only. Closing SSH will stop this process.
# For always-on operation use: sudo ./install-service.sh
set -euo pipefail

./mvnw -q package
if [ -z "${LUMENLINK_ROOM_PASSWORD_SHA256:-}" ]; then
  read -r -s -p "Room password for this debug server: " ROOM_PASSWORD
  echo
  if [ -z "${ROOM_PASSWORD}" ]; then
    echo "Room password must not be empty."
    exit 1
  fi
  export LUMENLINK_ROOM_PASSWORD_SHA256="$(printf '%s' "${ROOM_PASSWORD}" | sha256sum | awk '{print $1}')"
  unset ROOM_PASSWORD
fi
exec java -jar target/lumenlink-signal-0.1.0-SNAPSHOT.jar
