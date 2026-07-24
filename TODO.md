# LumenLink Development TODO

This document tracks the planned work for LumenLink. The active milestone is Windows-to-Windows remote control for personal use. The default transport policy is direct peer-to-peer traffic: the public server provides signaling and connection coordination, but does not carry screen, audio, input, or file-transfer traffic unless a future user explicitly enables relay fallback.

## 1. Foundation and Deployment

- [x] Create the Java 21 project structure.
- [x] Create an Ubuntu signaling-server release package.
- [x] Create Windows and Ubuntu client release packages.
- [x] Implement room-based peer discovery through the signaling server.
- [x] Exchange STUN candidates and begin basic ICE negotiation.
- [x] Replace fixed "controller" and "host" startup roles with a unified online-device model.
- [x] Publish device ID, display name, platform, application version, capabilities, and online state.
- [x] Add server-side room password verification for Windows client registration.
- [ ] Implement device key pairing and a trusted-device list.
- [x] Implement control-session requests, accept/reject flow, disconnect flow, and session expiry.
- [ ] Record local security-relevant session events without recording sensitive input contents.
- [ ] Add Windows startup and persistent background operation.
- [ ] Add Ubuntu systemd installation and persistent background operation.
- [ ] Package clients with a bundled runtime so Java installation is unnecessary for end users.
- [ ] Maintain deployment, port, configuration, and troubleshooting documentation.

## 2. Direct Connection and NAT Traversal

- [ ] Complete an end-to-end Windows-to-Windows direct UDP connection test.
- [ ] Defer Windows-to-Ubuntu and Ubuntu-to-Ubuntu direct connection tests until the Windows-to-Windows path is stable.
- [x] Show baseline runtime stats for resolution, FPS, bitrate, RTT, packet loss, and candidate path when WebRTC exposes them.
- [ ] Expand connection diagnostics with selected local/remote candidate addresses and clearer failure causes.
- [ ] Detect and prefer viable IPv6 direct paths.
- [ ] Add UPnP and NAT-PMP port mapping where available.
- [ ] Research and implement TCP hole punching where it is viable.
- [ ] Study OpenP2P-style NAT classification and multi-strategy connection selection.
- [ ] Provide selectable connection strategies: automatic, IPv6, UDP hole punching, TCP hole punching, and manual relay.
- [ ] Keep direct connection as the default; relay must be explicitly configured or approved.
- [ ] Add HTTPS/WSS, authentication, rate limits, and auditability to the signaling server.

## 3. Dynamic Device Sessions

- [x] Let every running client appear as an online device rather than a permanently assigned controller or controlled endpoint.
- [x] Allow either device to initiate a control request by selecting another online device.
- [x] Allow the same two devices to reverse control direction in a later session without restarting either client.
- [ ] Display device state such as online, locked, no user session, screen available, audio available, and file-transfer available.
- [ ] Define per-device policies: require local approval, allow trusted devices, view-only access, and deny remote control.
- [ ] Define per-session permissions separately for screen viewing, keyboard/mouse input, audio, clipboard, and files.
- [ ] Add a visible local emergency stop action and a configurable shortcut on the controlled machine.

## 4. Screen Sharing

- [x] Implement Windows screen capture.
- [ ] Implement Ubuntu screen capture for X11 and evaluate supported Wayland approaches separately.
- [x] Send a WebRTC video track between directly connected peers.
- [ ] Detect and negotiate supported codecs, starting with H.264 and VP8.
- [x] Support 30, 60, 90, and 120 FPS presets where capture and hardware allow them.
- [x] Support 2 Mbps, 8 Mbps, and 20 Mbps quality presets plus adaptive bitrate control.
- [ ] Support selecting and switching between multiple displays.
- [x] Use a single-primary-display model with controller-selected output resolution and normalized pointer remapping.
- [ ] Add real-machine validation for DPI scaling, non-100% Windows scale factors, and unusual aspect ratios.
- [ ] Provide original-size, fit-to-window, full-screen, and zoom viewing modes.
- [ ] Adapt frame rate, resolution, and bitrate under congestion and recover quality when bandwidth returns.
- [ ] Show real-time frame rate, bitrate, packet loss, and latency statistics.

## 5. Remote Input

- [x] Implement Windows keyboard and mouse input injection.
- [ ] Implement Ubuntu keyboard and mouse input injection, with separate capability handling for X11 and Wayland.
- [x] Map mouse coordinates correctly across scaling, window size, display rotation, and non-standard resolutions.
- [x] Support drag, scroll, multi-button mice, and keyboard combinations (clipboard sync still open).
- [ ] Provide view-only and control-enabled session modes.
- [ ] Handle protected interfaces such as UAC and security desktop through explicit capability reporting and safe degradation.

## 6. Background Operation and Locked Devices

- [ ] Implement a Windows service for durable device identity, signaling, trust policy, and online-state reporting.
- [ ] Implement a per-user interactive agent for screen capture, audio capture, and desktop input.
- [ ] Establish authenticated local IPC between the Windows service and user-session agent.
- [ ] Report locked, unlocked, no-user-session, and interactive-agent availability states to trusted peers.
- [ ] Allow a trusted controller to request a session while the target is locked and wait for the desktop agent to resume after a local unlock.
- [ ] Restore an authorized session automatically after the local desktop becomes available, subject to explicit policy.
- [ ] Evaluate privacy-screen and controlled-display-off options for supported hardware and operating systems.
- [ ] Do not transmit, store, or log an operating-system account password through normal LumenLink P2P channels.
- [ ] Treat pre-login or secure-desktop remote access as a separate, security-reviewed native integration project; do not attempt to bypass Windows lock-screen or UAC protections with simulated input.

## 7. Audio, Clipboard, and File Transfer

- [x] Wire the first Windows system-audio WebRTC track, controller playback, and host speaker mute.
- [ ] Complete a Windows-to-Windows audio end-to-end test with real speaker output.
- [ ] Use Opus audio encoding and WebRTC audio transport.
- [ ] Support audio device selection, mute controls, and audio/video synchronization.
- [ ] Implement standalone file transfer without requiring screen sharing.
- [ ] Use direct DataChannel transfer with chunking, integrity checks, progress, cancellation, and retry.
- [ ] Add resumable file transfers for interrupted direct connections.
- [ ] Let the receiving device choose its download directory and confirmation policy.
- [ ] Define text and file clipboard synchronization policies.

## 8. Security and Release Quality

- [ ] Use mutually authenticated device identities and encrypted session key negotiation.
- [ ] Implement trusted-device revocation, permission expiration, and session time limits.
- [ ] For broader use beyond the personal Windows build, add explicit authorization for control, audio, clipboard, and file-transfer capabilities.
- [ ] Apply least-privilege defaults and require first-connection confirmation unless pre-authorized.
- [ ] Add Windows code signing and an installer.
- [ ] Add an Ubuntu package format such as deb or AppImage.
- [ ] Add a secure update mechanism.
- [ ] Create an automated interoperability test matrix for Windows and Ubuntu, IPv4 and IPv6, LAN and NAT scenarios.

## Recommended Implementation Order

1. Stabilize Windows-to-Windows remote control on real machines.
2. Show direct-connection diagnostics in the Windows client.
3. Add Windows persistent startup/background operation.
4. Add device pairing/trust if the tool is used outside the owner's private devices.
5. Add system audio and independent file transfer.
6. Return to Ubuntu X11/Wayland capture and input support when needed.
7. Add IPv6, UPnP, TCP punching, and optional relay as advanced connection strategies.
