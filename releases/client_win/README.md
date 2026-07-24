# LumenLink Client for Windows

This folder is the active Java 21 Windows client source bundle. Copy it to each Windows machine that participates in the current Windows-to-Windows remote-control test.

## Install Java 21

Right-click `install-java21.cmd` and select **Run as administrator**. It downloads Temurin JDK 21, installs it silently, and sets machine-wide `JAVA_HOME` and `Path`. Open a new PowerShell window afterward.

## Start

Open PowerShell in this folder and run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start.ps1 -SignalUrl "ws://SERVER_PUBLIC_IP:8080/ws" -StunUrl "stun:SERVER_PUBLIC_IP:3478"
```

The first run downloads Maven and project dependencies. Later starts use the local cache.

On first launch, enter the room password configured on the Ubuntu server. The client saves that password in the local user profile and tries to go online automatically on later launches, which supports unattended startup after a remotely powered-on Windows machine boots.

The client makes outbound connections only:

```text
TCP 8080 or TCP 443    outbound WSS/WS signaling
UDP/TCP 3478           outbound STUN
UDP ephemeral ports    direct WebRTC candidate checks and session traffic
```

Do not configure a port-forward or inbound firewall exception for the client. Windows Firewall should allow the Java process to make outbound connections. The client must use the same signal and STUN addresses, plus the same network code, as the other Windows client.

The current release goes online under a shared network code after the room password is accepted, lists other Windows devices in that room, and lets either side request control of the other. Incoming requests are accepted automatically for this personal-use build. After that, it exchanges WebRTC offers, answers, and ICE candidates, sends the host's primary physical display over a direct WebRTC video track, scales that video to the selected output resolution, sends the host's experimental system-audio track to the controller, temporarily mutes the host speaker, and sends keyboard/mouse control over a direct DataChannel. The remote window shows runtime resolution, FPS, bitrate, RTT, packet loss, and candidate-path stats when WebRTC exposes them. Relay candidates are disabled.
