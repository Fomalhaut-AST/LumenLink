#!/usr/bin/env bash
# Foreground start for debugging only. Closing SSH will stop this process.
# For always-on operation use: sudo ./install-service.sh
set -euo pipefail

./mvnw -q package
export LUMENLINK_DATABASE_PATH="${LUMENLINK_DATABASE_PATH:-data/lumenlink.db}"
exec java -jar target/lumenlink-signal-0.1.0-SNAPSHOT.jar
