#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo apt-get install -y wget gpg ca-certificates coturn
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
sudo apt-get update
sudo apt-get install -y temurin-21-jdk
sudo install -m 644 config/turnserver.conf /etc/turnserver.conf
sudo systemctl enable --now coturn
