# LumenLink Client for Windows

This folder is a complete Java 21 Windows client source bundle. Copy it to a Windows machine with JDK 21 installed.

## Install Java 21

Right-click `install-java21.cmd` and select **Run as administrator**. It downloads Temurin JDK 21, installs it silently, and sets machine-wide `JAVA_HOME` and `Path`. Open a new PowerShell window afterward.

## Start

Open PowerShell in this folder and run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start.ps1 -SignalUrl "ws://SERVER_PUBLIC_IP:8080/ws" -StunUrl "stun:SERVER_PUBLIC_IP:3478"
```

The first run downloads Maven and project dependencies. Later starts use the local cache.

The client makes outbound connections only:

```text
TCP 8080 or TCP 443    outbound WSS/WS signaling
UDP/TCP 3478           outbound STUN
UDP ephemeral ports    direct WebRTC candidate checks and session traffic
```

Do not configure a port-forward or inbound firewall exception for the client. Windows Firewall should allow the Java process to make outbound connections. The client must use the same signal and STUN addresses, plus the same session code, as the other client.

The current release joins the supplied signaling server, exchanges WebRTC offers, answers, and ICE candidates, then attempts a direct UDP DataChannel connection with relay candidates disabled. Screen transport and input injection are not yet wired into the UI, so it cannot yet perform remote control.
