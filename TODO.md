# LumenLink Development TODO

This document tracks the planned work for LumenLink. The product scope is Windows-to-Windows remote control for personal use. The Ubuntu server provides account, signaling, and connection-coordination services, while screen, audio, input, clipboard, and file-transfer traffic remains direct peer-to-peer traffic. An Ubuntu client and server-side traffic relay are not in scope.

## 1. Foundation and Deployment

- [x] Create the Java 21 project structure.
- [x] Create an Ubuntu signaling-server release package.
- [x] Create a Windows client release package.
- [x] Implement room-based peer discovery through the signaling server.
- [x] Exchange STUN candidates and begin basic ICE negotiation.
- [x] Replace fixed "controller" and "host" startup roles with a unified online-device model.
- [x] Publish device ID, display name, platform, application version, capabilities, and online state.
- [x] Add account registration/login with Argon2id password hashes and SQLite persistence.
- [x] Authenticate WSS device registration with revocable, expiring device tokens.
- [x] Protect saved Windows device tokens with DPAPI; do not persist account passwords.
- [x] Limit the server to 10 accounts and 20 concurrent authentication requests.
- [x] Add password changes, device listing/revocation, logout, and account deletion.
- [x] Reconnect authenticated Windows clients every five seconds after signaling interruption.
- [x] Add bounded client/server operational logs without credentials, SDP, or input contents.
- [x] Implement control-session requests, accept/reject flow, disconnect flow, and session expiry.
- [ ] Record local security-relevant session events without recording sensitive input contents.
- [ ] Add Windows startup and persistent background operation.
- [x] Add Ubuntu systemd installation and persistent background operation.
- [ ] Package clients with a bundled runtime so Java installation is unnecessary for end users.
- [ ] Maintain deployment, port, configuration, and troubleshooting documentation.

## 2. Direct Connection and NAT Traversal

- [ ] Complete an end-to-end Windows-to-Windows direct UDP connection test.
- [x] Show baseline runtime stats for resolution, FPS, bitrate, RTT, packet loss, and candidate path when WebRTC exposes them.
- [ ] Expand connection diagnostics with selected local/remote candidate addresses and clearer failure causes.
- [ ] Detect and prefer viable IPv6 direct paths.
- [ ] Add UPnP and NAT-PMP port mapping where available.
- [ ] Research and implement TCP hole punching where it is viable.
- [ ] Study OpenP2P-style NAT classification and multi-strategy connection selection.
- [ ] Provide automatic direct-connection strategy selection across IPv6, UDP hole punching, TCP hole punching, UPnP, and NAT-PMP.
- [x] Add HTTPS/WSS deployment and account authentication to the signaling server.
- [ ] Add authentication rate limits and operational security auditing to the signaling server.

## 3. Dynamic Device Sessions

- [x] Let every running client appear as an online device rather than a permanently assigned controller or controlled endpoint.
- [x] Allow either device to initiate a control request by selecting another online device.
- [x] Allow the same two devices to reverse control direction in a later session without restarting either client.
- [ ] Display device state such as online, locked, no user session, screen available, audio available, and file-transfer available.

## 4. Screen Sharing

- [x] Implement Windows screen capture.
- [x] Send a WebRTC video track between directly connected peers.
- [x] Support 30, 60, 90, and 120 FPS presets where capture and hardware allow them.
- [x] Provide low-power, balanced, quality, and high-frame-rate profiles plus custom resolution, FPS, and bitrate controls.
- [x] Drop stale controller frames and bound JavaFX rendering work so slow devices do not accumulate display latency.
- [x] Keep secure-desktop capture and WASAPI loopback idle outside active remote-control sessions.
- [ ] Support selecting and switching between multiple displays.
- [x] Use a single-primary-display model with controller-selected output resolution and normalized pointer remapping.
- [ ] Add real-machine validation for DPI scaling, non-100% Windows scale factors, and unusual aspect ratios.
- [ ] Provide original-size, fit-to-window, full-screen, and zoom viewing modes.
- [ ] Adapt frame rate, resolution, and bitrate under congestion and recover quality when bandwidth returns.

## 5. Remote Input

- [x] Implement Windows keyboard and mouse input injection.
- [x] Map mouse coordinates correctly across scaling, window size, display rotation, and non-standard resolutions.
- [x] Support drag, scroll, multi-button mice, and keyboard combinations (clipboard sync still open).
- [ ] Handle UAC prompts through explicit capability reporting and safe degradation; do not treat UAC elevation as ordinary desktop input.

## 6. Background Operation and Locked Devices

- [ ] Implement a Windows service for durable device identity, signaling, and online-state reporting.
- [ ] Implement a per-user interactive agent for screen capture, audio capture, and desktop input.
- [x] Establish OS-identity-restricted, memory-only IPC between the Windows secure-desktop service and user-session client.
- [ ] Report locked, unlocked, no-user-session, and interactive-agent availability states to other devices in the authenticated account.
- [x] Add a Windows native secure-desktop component that can capture the lock screen and inject keyboard and mouse input with the minimum required privileges.
- [x] Allow another device in the authenticated account to view and control the Windows lock screen, including entering the Windows password remotely.
- [x] Support the Windows secure-attention sequence where required through documented Windows mechanisms without bypassing the secure-desktop boundary.
- [x] Transport lock-screen password keystrokes only as ordinary end-to-end encrypted input events; never extract, persist, or log credentials or keyboard contents on the client or server.
- [x] Switch capture and input between the lock screen and the interactive user desktop without disconnecting the authenticated remote-control session.
- [ ] Validate lock, remote unlock, user switching, sign-out, and reconnect behavior on supported Windows 10 and Windows 11 versions.

## 7. Audio, Clipboard, and File Transfer

- [x] Capture the Windows system mix through WASAPI loopback and send it as a direct WebRTC audio track.
- [x] Add bounded controller playback, controller mute/volume controls, and temporary host-speaker mute with state restoration.
- [ ] Complete a Windows-to-Windows audio end-to-end test with real speaker output.
- [ ] Support selecting the controller output device and validate audio/video synchronization on real machines.
- [ ] Implement standalone file transfer without requiring screen sharing.
- [ ] Use direct DataChannel transfer with chunking, integrity checks, progress, cancellation, and retry.
- [ ] Add resumable file transfers for interrupted direct connections.
- [ ] Let the receiving device choose its download directory.
- [ ] Implement text and file clipboard synchronization.

## 8. Security and Release Quality

- [ ] Add Windows code signing and an installer.
- [ ] Add a secure update mechanism.
- [ ] Create an automated Windows-to-Windows interoperability test matrix for IPv4 and IPv6, LAN and NAT scenarios.

## Recommended Implementation Order

1. Stabilize Windows-to-Windows remote control on real machines.
2. Show direct-connection diagnostics in the Windows client.
3. Add authentication rate limiting and security-event auditing.
4. Add Windows persistent startup/background operation and lock-screen remote control.
5. Add system audio, clipboard synchronization, and independent file transfer.
6. Add IPv6, UPnP, NAT-PMP, and viable TCP punching as direct-connection strategies.
