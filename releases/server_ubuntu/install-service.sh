#!/usr/bin/env bash
# Install LumenLink signaling as a systemd service that stays up after logout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVICE_USER="${SUDO_USER:-${USER}}"
if [ "${SERVICE_USER}" = "root" ]; then
  echo "Run this script with sudo from your normal account, e.g.:"
  echo "  sudo ./install-service.sh"
  exit 1
fi

SERVICE_HOME="$(getent passwd "${SERVICE_USER}" | cut -d: -f6)"
APP_DIR="${ROOT}"
DATA_DIR="/var/lib/lumenlink"
LOG_DIR="/var/log/lumenlink"
JAR="${APP_DIR}/target/lumenlink-signal-0.1.0-SNAPSHOT.jar"
UNIT_PATH="/etc/systemd/system/lumenlink-signal.service"
JAVA_BIN="$(command -v java || true)"

if [ -z "${JAVA_BIN}" ]; then
  echo "java not found. Run ./install-prerequisites.sh first."
  exit 1
fi

echo "Building jar..."
cd "${APP_DIR}"
./mvnw -q package

if [ ! -f "${JAR}" ]; then
  echo "Missing jar: ${JAR}"
  exit 1
fi

install -d -m 750 -o "${SERVICE_USER}" -g "${SERVICE_USER}" "${DATA_DIR}"
install -d -m 750 -o "${SERVICE_USER}" -g "${SERVICE_USER}" "${LOG_DIR}"

# Stop any foreground instance on 8080 so systemd can bind the port.
pkill -f 'lumenlink-signal-0.1.0-SNAPSHOT.jar' 2>/dev/null || true
fuser -k 8080/tcp 2>/dev/null || true

echo "Writing ${UNIT_PATH} (user=${SERVICE_USER}, dir=${APP_DIR})"
cat >"${UNIT_PATH}" <<EOF
[Unit]
Description=LumenLink WebRTC signaling service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_USER}
WorkingDirectory=${APP_DIR}
# Keep heap small: signaling only; never carries media.
ExecStart=${JAVA_BIN} -Xms32m -Xmx128m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -jar ${JAR}
Restart=always
RestartSec=3
Environment=LUMENLINK_SIGNAL_PORT=8080
Environment=LUMENLINK_DATABASE_PATH=${DATA_DIR}/lumenlink.db
Environment=LUMENLINK_LOG_DIR=${LOG_DIR}
KillMode=process
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable lumenlink-signal
systemctl restart lumenlink-signal
systemctl --no-pager --full status lumenlink-signal

echo
echo "Installed. The service keeps running after you close the console."
echo "  status:  sudo systemctl status lumenlink-signal"
echo "  logs:    sudo journalctl -u lumenlink-signal -f"
echo "  restart: sudo systemctl restart lumenlink-signal"
echo "  stop:    sudo systemctl stop lumenlink-signal"
