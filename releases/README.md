# LumenLink Test Releases

**Source of truth for testing:** only code under `releases/` is deployed and run during tests.
Any client or server change made in the repo root must be synced into the matching `releases/*` package in the same change.

Transfer only the folder that matches the target platform. Each folder is self-contained and includes its own README.

| Folder | Target | Purpose |
| --- | --- | --- |
| `server_ubuntu` | Public Ubuntu 22.04 server | Signaling service and STUN setup |
| `client_win` | Windows 10/11 x64 | Current controller/host desktop client |
| `client_ubuntu` | Ubuntu desktop x64 | Deferred client target; keep for later Linux work |

Start the server first. For the current milestone, copy `client_win` to two Windows machines, run both with the same network code and server addresses, then request control from either Windows client.

The server only handles signaling and STUN. Successful screen and control sessions are direct client-to-client WebRTC connections. Relay candidates are disabled, so difficult NAT pairs can fail instead of sending session data through the server.
