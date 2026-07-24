# LumenLink Server for Ubuntu 22.04

This folder contains the complete server-side deployment. It runs two separate services:

- `SignalServer`: WebSocket messages for WebRTC setup only, listening on TCP `8080` by default.
- Coturn in STUN-only mode, listening on UDP/TCP `3478`.

No video, remote-control, clipboard, or file payload is accepted or forwarded by this server.

The signaling service can require a shared room password. `install-service.sh` prompts for this password, stores only its SHA-256 hash in the systemd unit, and clients must provide the same password when registering.

## Why the process dies when you close the console

`./start.sh` runs Java in the **foreground**. When the Aliyun web console / SSH session ends, the shell receives `SIGHUP` and kills child processes.

Use **systemd** for production and long tests so the service survives logout and reboots.

## Memory on a small VPS (2 GiB)

LumenLink does **not** relay screen/control traffic, so the server stays light.

| Component | Typical RSS | Notes |
| --- | --- | --- |
| `lumenlink-signal` (Java) | **~80–200 MiB** with capped heap | Default install uses `-Xmx128m` |
| Coturn (STUN-only) | **~10–40 MiB** | No TURN relay allocations |
| Idle OS + other apps | varies | Leave headroom for them |

Without heap caps, a Temurin 21 JVM may reserve more and feel heavy on 2 GiB. After install, check:

```bash
ps -o pid,rss,cmd -C java
# RSS is KiB; divide by 1024 for MiB
free -h
```

## Always-on install (recommended)

```bash
cd ~/lumenlink   # or wherever this folder lives
chmod +x mvnw install-prerequisites.sh install-service.sh start.sh
./install-prerequisites.sh
sudo ./install-service.sh
```

`install-service.sh` will:

1. Build the jar
2. Prompt for the room password and write its SHA-256 hash into the service environment
3. Stop any old foreground process on port 8080
4. Install `/etc/systemd/system/lumenlink-signal.service`
5. `enable --now` so it starts on boot and restarts on crash

Useful commands:

```bash
sudo systemctl status lumenlink-signal
sudo journalctl -u lumenlink-signal -f
sudo systemctl restart lumenlink-signal
sudo systemctl stop lumenlink-signal
```

After updating server source code:

```bash
cd ~/lumenlink
./mvnw -q package
sudo systemctl restart lumenlink-signal
```

## Temporary foreground start (debug only)

```bash
./start.sh
```

`start.sh` prompts for a room password if `LUMENLINK_ROOM_PASSWORD_SHA256` is not already set. Closing the console stops this process. Prefer systemd above.

## Temporary background without systemd

```bash
./mvnw -q package
nohup java -jar target/lumenlink-signal-0.1.0-SNAPSHOT.jar > signal.log 2>&1 &
disown
```

This survives logout but does **not** auto-restart on crash or reboot. Prefer systemd.

## Firewall / security group

```text
TCP 8080     signaling for initial testing
UDP 3478     STUN
TCP 3478     STUN fallback
```

Clients:

```text
Signal URL: ws://SERVER_PUBLIC_IP:8080/ws
STUN URL:   stun:SERVER_PUBLIC_IP:3478
```

Use a domain name and TLS before any real use. Put a reverse proxy in front of TCP `8080` so clients use `wss://signal.example.com/ws` on TCP `443`. STUN can remain on port `3478`.

## Direct-only limitation

The supplied Coturn configuration explicitly disables UDP and TCP relays. This preserves the rule that session traffic never traverses the server. If both clients are behind incompatible NATs or UDP is blocked, their connection will fail. Adding TURN later is a deliberate product policy change, not a server tuning step.
