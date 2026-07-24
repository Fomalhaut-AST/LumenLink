#!/usr/bin/env bash
set -euo pipefail

export LUMENLINK_SIGNAL_URL="${1:-ws://8.148.70.189:8080/ws}"
export LUMENLINK_STUN_URL="${2:-stun:8.148.70.189:3478}"
./mvnw javafx:run
