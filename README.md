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

Windows devices register or log into an account through HTTPS, then authenticate WSS signaling with a random device token. Password hashes, devices, and token hashes persist in SQLite on Ubuntu. Windows protects the local token with DPAPI and reconnects automatically on later launches.

### Windows lock-screen control

Install the Windows secure-desktop host once from an elevated PowerShell window before testing lock-screen control:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
cd .\windows-host
.\install-service.ps1
```

The installer publishes a self-contained x64 host when a bundled build is unavailable, installs it as the automatic `LumenLinkSecureDesktop` LocalSystem service, and enables the Windows policy that permits services to generate the secure-attention sequence. The Java UI remains a normal user process. A restricted, memory-only IPC mapping lets the service-side agent provide lock-screen frames and consume input only from the currently logged-in Windows user.

Keep the Windows client running and online before locking the workstation. A controller in the same LumenLink account can open the device, see the Windows lock screen, type the Windows password, and continue the same WebRTC session after unlock. `Ctrl+Alt+Delete` in the remote window uses the Windows `SendSAS` service path. Passwords and keyboard events are not persisted or logged. This supports locking and unlocking an existing console session; it does not provide remote access before the first Windows sign-in after boot or after signing out.

The same Windows host captures the default playback endpoint through WASAPI loopback as 48 kHz, 16-bit stereo PCM. Java sends those frames through a WebRTC audio track negotiated alongside the video track. The remote window provides an audio checkbox and volume slider; playback uses a bounded queue so a slow output device cannot block WebRTC decoding or accumulate unbounded latency. The office speaker is muted only while loopback capture is available and its previous mute state is restored when the session ends. Audio PCM stays in memory and is never sent through the signaling server or written to logs.

To remove the service and restore the previous software-SAS policy value:

```powershell
.\uninstall-service.ps1
```

The account window lists logged-in and online devices, changes the password, revokes individual devices, and deletes the account. A password change preserves the current device token while revoking and disconnecting every other device. When signaling is interrupted or the Ubuntu service restarts, Windows ends any active control session and retries WSS every five seconds until it reconnects or the user explicitly goes offline.

Client and server operational logs use bounded rotation and fixed event names. Passwords, device tokens, SDP/ICE payloads, and keyboard contents are not passed to the file loggers.

## Ubuntu server responsibilities

1. Run a TLS-terminated WSS signaling service on `443`.
2. Run Coturn as a STUN service on UDP/TCP `3478` and expose the corresponding firewall rules.
3. Do not give clients a TURN URL or credentials while direct-only mode is required.
4. Log only operational metadata necessary for abuse prevention; do not persist SDP or session codes.

## Current scope

Clients no longer start as a fixed host or controller. Each client goes online with a persistent device identity, appears in its account's private device list, and either side can request control of another online Windows device. For the current personal-use Windows build, incoming control requests are accepted automatically. WebRTC offer/answer/ICE then runs over the direct-only path.

Windows clients use a single-primary-display remote-control model for the current milestone. The host captures the primary physical display, scales the outgoing video track to the controller-selected resolution when requested, and maps controller pointer input back into the primary display bounds. On workstation lock, the WebRTC sender switches to the service-provided Winlogon desktop frames and switches back without renegotiating after unlock. Resolution, FPS, and max bitrate are selectable before a session starts. The remote window displays runtime WebRTC stats for actual resolution, FPS, video/audio bitrate, RTT, packet loss, and candidate path where available. Windows system audio is captured with WASAPI loopback and travels only over the direct WebRTC connection.

The default `Balanced` profile uses 720p, 24 FPS, and 2.5 Mbps. `Low power` reduces this to 480p, 10 FPS, and 0.8 Mbps for older controllers or controlled devices, while the advanced controls remain available for custom settings. The controller renders only the newest decoded frame at the negotiated display rate, so a slow JavaFX UI drops stale frames instead of accumulating seconds of latency. Secure-desktop video capture and WASAPI loopback remain idle until a remote session actually requests them.
