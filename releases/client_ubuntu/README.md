# LumenLink Client for Ubuntu Desktop

This folder is kept for later Ubuntu client work. The active milestone is Windows-to-Windows remote control, so do not use this package for current remote-control acceptance tests.

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

Ubuntu X11 will be the first Linux target when this work resumes. Wayland requires PipeWire and xdg-desktop-portal integration, which is not included in the current Java release.

The current Windows client already has the active screen/video/input path. Ubuntu screen capture, input injection, capability detection, and Wayland behavior are deferred.
