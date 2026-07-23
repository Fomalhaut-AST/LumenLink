#!/usr/bin/env bash
set -euo pipefail

./mvnw -q package
exec java -jar target/lumenlink-signal-0.1.0-SNAPSHOT.jar
