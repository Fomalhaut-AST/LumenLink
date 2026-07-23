# LumenLink

LumenLink is a Java 21 desktop foundation for direct peer-to-peer remote assistance on Windows and Ubuntu.

## Network model

```text
Controller ---- encrypted WebRTC media + DataChannel ---- Host
     \                                                    /
      \---------- WSS signaling + STUN discovery --------/
                         Ubuntu server
```

The public server exchanges only WebRTC offers, answers, and ICE candidates through WSS. It also exposes STUN for NAT binding discovery. Video frames, remote-control events, clipboard data, and files are sent only through the direct WebRTC peer connection.

The client deliberately disables TURN relay candidates. This guarantees that a successful session never sends session payload through the Ubuntu server, but it also means a connection can fail when both peers are behind CGNAT, symmetric NAT, UDP-blocking networks, or restrictive corporate firewalls. No implementation can promise direct connectivity for every pair of residential networks under that constraint.

## Technology

- Java 21 and JavaFX for the desktop application.
- [webrtc-java](https://jrtc.dev/) for native WebRTC, desktop capture, and DataChannels on Windows and Linux.
- Java's built-in HTTP WebSocket client plus WSS for signaling.
- STUN running on the Ubuntu server. TURN is intentionally not configured in this direct-only build.

## Build and run

The project includes a Maven Wrapper, so a system-wide Maven installation is unnecessary.

```powershell
.\mvnw.cmd test
.\mvnw.cmd javafx:run
```

Set these optional environment variables before running:

```powershell
$env:LUMENLINK_SIGNAL_URL = "wss://signal.example.com/ws"
$env:LUMENLINK_STUN_URL = "stun:signal.example.com:3478"
```

## Ubuntu server responsibilities

1. Run a TLS-terminated WSS signaling service on `443`.
2. Run Coturn as a STUN service on UDP/TCP `3478` and expose the corresponding firewall rules.
3. Do not give clients a TURN URL or credentials while direct-only mode is required.
4. Log only operational metadata necessary for abuse prevention; do not persist SDP or session codes.

## Current scope

This first Java revision establishes the Java 21 build, UI, signaling protocol, direct-only ICE policy, local control-permission boundary, and test coverage. The next implementation slice wires offer/answer/candidate handling into `WebRtcEngine`, desktop source selection, JavaFX video rendering, and platform-specific input injection.
