# LumenLink Server for Ubuntu 22.04

This folder contains the complete server-side deployment. It runs two separate services:

- `SignalServer`: WebSocket messages for WebRTC setup only, listening on TCP `8080` by default.
- Coturn in STUN-only mode, listening on UDP/TCP `3478`.

No video, remote-control, clipboard, or file payload is accepted or forwarded by this server.

## Initial test deployment

Copy this folder to the Ubuntu server, then run:

```bash
chmod +x mvnw install-prerequisites.sh start.sh
./install-prerequisites.sh
./start.sh
```

Open these security-group and host firewall ports:

```text
TCP 8080     signaling for initial testing
UDP 3478     STUN
TCP 3478     STUN fallback
```

For the initial test, clients use:

```text
Signal URL: ws://SERVER_PUBLIC_IP:8080/ws
STUN URL:   stun:SERVER_PUBLIC_IP:3478
```

Use a domain name and TLS before any real use. Put a reverse proxy in front of TCP `8080` so clients use `wss://signal.example.com/ws` on TCP `443`. STUN can remain on port `3478`.

## Persistent service

After `./start.sh` produces the JAR, create a non-login `lumenlink` user, copy this directory to `/opt/lumenlink/server_ubuntu`, then install `systemd/lumenlink-signal.service` as `/etc/systemd/system/lumenlink-signal.service` and run:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now lumenlink-signal
sudo systemctl status lumenlink-signal
```

## Direct-only limitation

The supplied Coturn configuration explicitly disables UDP and TCP relays. This preserves the rule that session traffic never traverses the server. If both clients are behind incompatible NATs or UDP is blocked, their connection will fail. Adding TURN later is a deliberate product policy change, not a server tuning step.
