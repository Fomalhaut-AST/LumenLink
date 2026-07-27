#!/usr/bin/env bash
# Install an nginx TLS endpoint for HTTPS account APIs and WSS signaling.
set -euo pipefail

if [ "${EUID}" -ne 0 ]; then
  echo "Run with sudo: sudo ./install-wss.sh"
  exit 1
fi

read -r -p "Public DNS name or fixed public IPv4 address: " SIGNAL_HOST
read -r -p "Email address for certificate renewal notices: " CERT_EMAIL
if [ -z "${SIGNAL_HOST}" ] || [ -z "${CERT_EMAIL}" ]; then
  echo "A public host and email address are required."
  exit 1
fi

SITE="/etc/nginx/sites-available/lumenlink"
WEBROOT="/var/www/lumenlink-acme"
install -d -m 755 "${WEBROOT}/.well-known/acme-challenge"

write_proxy_location() {
  cat <<'EOF'
    client_max_body_size 64k;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
EOF
}

{
  echo "server {"
  echo "    listen 80;"
  echo "    listen [::]:80;"
  echo "    server_name ${SIGNAL_HOST};"
  echo "    location /.well-known/acme-challenge/ { root ${WEBROOT}; }"
  write_proxy_location
  echo "}"
} >"${SITE}"

ln -sfn "${SITE}" /etc/nginx/sites-enabled/lumenlink
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx

if [[ "${SIGNAL_HOST}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  certbot certonly --non-interactive --agree-tos -m "${CERT_EMAIL}" \
    --preferred-profile shortlived --webroot --webroot-path "${WEBROOT}" --ip-address "${SIGNAL_HOST}"
  {
    echo "server {"
    echo "    listen 80;"
    echo "    listen [::]:80;"
    echo "    server_name ${SIGNAL_HOST};"
    echo "    location /.well-known/acme-challenge/ { root ${WEBROOT}; }"
    echo "    location / { return 301 https://\$host\$request_uri; }"
    echo "}"
    echo "server {"
    echo "    listen 443 ssl;"
    echo "    listen [::]:443 ssl;"
    echo "    server_name ${SIGNAL_HOST};"
    echo "    ssl_certificate /etc/letsencrypt/live/${SIGNAL_HOST}/fullchain.pem;"
    echo "    ssl_certificate_key /etc/letsencrypt/live/${SIGNAL_HOST}/privkey.pem;"
    write_proxy_location
    echo "}"
  } >"${SITE}"
else
  certbot --nginx --non-interactive --agree-tos --redirect -m "${CERT_EMAIL}" -d "${SIGNAL_HOST}"
fi

nginx -t
systemctl reload nginx
systemctl enable --now snap.certbot.renew.timer 2>/dev/null || true

echo "WSS ready: wss://${SIGNAL_HOST}/ws"
echo "Account API: https://${SIGNAL_HOST}/api/accounts/login"
