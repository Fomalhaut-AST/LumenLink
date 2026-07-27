# LumenLink Client for Windows

This is the active Windows 10/11 x64 test bundle. It requires Java 21 until a bundled runtime is added.

## Start

Install the secure-desktop service once from an elevated PowerShell window. The bundled host executable is used when present; otherwise the script requires the .NET 9 SDK to publish it first.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows-host\install-service.ps1
```

The service starts automatically with Windows. It runs the lock-screen capture/input agent as LocalSystem while the Java UI keeps normal user privileges. The installer also enables service-generated `Ctrl+Alt+Delete` and saves the previous policy value so `uninstall-service.ps1` can restore it.

The service also provides Windows WASAPI system-audio loopback. Re-run `install-service.ps1` after updating an older test bundle because the audio-capable IPC protocol requires the matching service executable and Java client.

Then start the client normally:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start.ps1 -SignalUrl "wss://SERVER_PUBLIC_IP/ws" -StunUrl "stun:SERVER_PUBLIC_IP:3478"
```

The WSS certificate must be trusted by Windows and must match the IP address or DNS name in `SignalUrl`.

On the first device, enter a username and a password of at least 10 characters and select **Create account**. On later devices, enter the same credentials and select **Log in**. All devices logged into that account share its private online-device list.

The password is sent only through HTTPS and is never stored by the client. The server returns a random device token, which is encrypted for the current Windows user with DPAPI and saved at `%USERPROFILE%\.lumenlink\auth-token.dpapi`. Later launches reconnect automatically. **Log out** revokes the server token and deletes the local DPAPI file.

The **Account** window changes the password, lists logged-in/online devices, revokes an individual device, and deletes the account. Changing the password signs out every other device. If signaling is interrupted or the server restarts, the client ends any active remote-control session and retries WSS every five seconds until it reconnects or the user goes offline.

To use lock-screen control, leave the client running and online before pressing `Win+L`. The remote window switches to the Windows lock screen without ending the P2P session. Keyboard and mouse input, including passwords with common punctuation, is injected on the secure desktop; `Ctrl+Alt+Delete` is delegated to the Windows service through `SendSAS`. After Windows accepts the password, the same WebRTC session switches back to the normal desktop. Passwords and keyboard contents are never extracted, persisted, or logged.

Windows system audio is captured from the host's default playback endpoint rather than from its microphone. The remote window's **Audio** checkbox controls mute and the volume slider controls local playback gain. While audio capture is active, LumenLink temporarily mutes the host speaker and restores its previous mute state when the session closes. Audio frames remain in memory and travel only inside the direct WebRTC connection.

The default **Balanced** performance profile uses 720p, 24 FPS, and 2.5 Mbps. Select **Low power** for 480p, 10 FPS, and 0.8 Mbps when either computer has a slower CPU/GPU or the network is limited. The controller drops superseded decoded frames instead of queueing them, and the secure-desktop video and system-audio capture paths stay idle while no remote session is active.

This feature unlocks an existing console session. It does not make the Java client available before the first sign-in after boot or after the user signs out.

Operational logs rotate at `%USERPROFILE%\.lumenlink\logs` as five 2 MiB files. They contain fixed event names and exception classes only; passwords, device tokens, SDP, ICE payloads, and keyboard contents are excluded.

The secure-desktop service writes a separate bounded 2 MiB log plus one rotated backup at `%ProgramData%\LumenLink\logs\secure-desktop.log`. It records service/agent lifecycle and error classes only.

The client makes outbound connections only:

```text
TCP 443              HTTPS account API and WSS signaling
UDP/TCP 3478         STUN
UDP ephemeral ports  direct WebRTC checks and session traffic
```

Screen, audio, and remote-control traffic remain direct peer-to-peer. TURN relay candidates are disabled, so incompatible NAT pairs can fail rather than relaying session data through the server.
