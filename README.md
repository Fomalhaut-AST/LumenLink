# LumenLink

LumenLink is a Java 21 desktop foundation for direct peer-to-peer remote assistance. The current implementation target is Windows-to-Windows remote control; Ubuntu client support is deferred until the Windows path is stable.

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

The signaling server can require a room password. Windows clients keep the server IP/STUN defaults visible in the UI, ask for the room password on first run, save it in the local user profile, and try to go online automatically on later launches.

## Ubuntu server responsibilities

1. Run a TLS-terminated WSS signaling service on `443`.
2. Run Coturn as a STUN service on UDP/TCP `3478` and expose the corresponding firewall rules.
3. Do not give clients a TURN URL or credentials while direct-only mode is required.
4. Log only operational metadata necessary for abuse prevention; do not persist SDP or session codes.

## Current scope

Clients no longer start as a fixed host or controller. Each client goes online with a persistent device identity, appears in a shared network-code device list, and either side can request control of another online Windows device. For the current personal-use Windows build, incoming control requests are accepted automatically. WebRTC offer/answer/ICE then runs over the direct-only path.

Windows clients use a single-primary-display remote-control model for the current milestone. The host captures the primary physical display, scales the outgoing video track to the controller-selected resolution when requested, and maps controller pointer input back into the primary display bounds. Resolution, FPS, and max bitrate are selectable before a session starts. The remote window displays runtime WebRTC stats for actual resolution, FPS, video/audio bitrate, RTT, packet loss, and candidate path where available. The Windows audio path is wired as an experimental host-system-audio WebRTC track with controller-side playback and temporary host speaker mute during the session. Ubuntu screen capture, audio, and input injection are not in the active test scope yet.
