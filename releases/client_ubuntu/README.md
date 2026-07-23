# LumenLink Client for Ubuntu Desktop

This folder is a complete Java 21 Ubuntu desktop client source bundle. It requires an Ubuntu desktop session; it is not intended for the public Ubuntu signaling server.

## Install and start

```bash
chmod +x mvnw install-prerequisites.sh start.sh
./install-prerequisites.sh
./start.sh ws://SERVER_PUBLIC_IP:8080/ws stun:SERVER_PUBLIC_IP:3478
```

The client needs only outbound network access:

```text
TCP 8080 or TCP 443    outbound WSS/WS signaling
UDP/TCP 3478           outbound STUN
UDP ephemeral ports    direct WebRTC candidate checks and session traffic
```

No router port-forwarding or incoming firewall rule is required. It must use the same signal and STUN addresses plus the same session code as the peer.

Ubuntu X11 is the target environment for screen capture and input injection. Wayland requires PipeWire and xdg-desktop-portal integration, which is not included in this initial Java release.

The current release joins the supplied signaling server, exchanges WebRTC offers, answers, and ICE candidates, then attempts a direct UDP DataChannel connection with relay candidates disabled. Video rendering and input injection remain the next implementation stage, so it cannot yet perform an end-to-end remote-control session.
