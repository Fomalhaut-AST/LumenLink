# LumenLink Server for Ubuntu 22.04

This package runs an account-authenticated signaling service and Coturn in STUN-only mode. Screen, audio, input, clipboard, and file data never pass through this server.

## Services and persisted data

- Nginx terminates HTTPS/WSS on TCP 443 and proxies only to `127.0.0.1:8080`.
- `lumenlink-signal` provides account registration/login and WebRTC signaling.
- SQLite persists accounts, Argon2id password hashes, registered devices, and token hashes at `/var/lib/lumenlink/lumenlink.db`.
- Operational logs rotate under `/var/log/lumenlink` as five 5 MiB files.
- Coturn provides STUN on UDP/TCP 3478. TURN relay is disabled.

Online presence and active WebRTC sessions are intentionally memory-only. After a server restart, clients reconnect with their saved device tokens and appear online again.

## Install

```bash
cd ~/lumenlink
chmod +x mvnw install-prerequisites.sh install-service.sh install-wss.sh start.sh
./install-prerequisites.sh
sudo ./install-service.sh
sudo ./install-wss.sh
```

`install-wss.sh` accepts either the fixed public IPv4 address or a DNS name. For an IP address it requests a short-lived Let's Encrypt IP certificate and enables automatic renewal. The Windows client must use the exact matching endpoint, for example `wss://8.148.70.189/ws`.

Open these cloud security-group/firewall ports:

```text
TCP 80       certificate validation and HTTPS redirect
TCP 443      HTTPS account API and WSS signaling
UDP 3478     STUN
TCP 3478     STUN fallback
```

Do not expose TCP 8080. The Java service binds it only on `127.0.0.1`.

## Operation

```bash
sudo systemctl status lumenlink-signal
sudo journalctl -u lumenlink-signal -f
sudo tail -f /var/log/lumenlink/server-0.log
sudo systemctl restart lumenlink-signal
```

After updating the source:

```bash
cd ~/lumenlink
./mvnw -q package
sudo systemctl restart lumenlink-signal
```

The systemd unit starts on boot and restarts after crashes, so closing SSH or the Aliyun console does not stop it.

## Backup

Stop the service before copying the SQLite database so the database and WAL are consistent:

```bash
sudo systemctl stop lumenlink-signal
sudo cp /var/lib/lumenlink/lumenlink.db /var/lib/lumenlink/lumenlink.db.backup
sudo systemctl start lumenlink-signal
```

The first account is created from the Windows client. Usernames are case-insensitive and passwords must contain 10-256 characters. Device tokens expire after 90 days and the server stores only their SHA-256 digests.

The server permits at most 10 registered accounts and processes at most 20 registration/login requests concurrently. Additional concurrent requests receive HTTP 429; once 10 accounts exist, further registration is rejected while existing accounts can continue to log in.

Account management supports password changes, device listing, individual device revocation, current-device logout, and account deletion. A password change preserves the current device token and immediately revokes every other device token.

Operational logs contain fixed event names and exception classes only. Request bodies, passwords, account tokens, SDP, ICE payloads, and remote-input contents are never passed to the file logger.
