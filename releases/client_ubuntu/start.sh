#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: ./start.sh ws://SERVER_PUBLIC_IP:8080/ws stun:SERVER_PUBLIC_IP:3478"
  exit 1
fi

export LUMENLINK_SIGNAL_URL="$1"
export LUMENLINK_STUN_URL="$2"
./mvnw javafx:run
