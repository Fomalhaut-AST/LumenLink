# LumenLink Test Releases

Transfer only the folder that matches the target platform. Each folder is self-contained and includes its own README.

| Folder | Target | Purpose |
| --- | --- | --- |
| `server_ubuntu` | Public Ubuntu 22.04 server | WSS signaling service and STUN setup |
| `client_win` | Windows 10/11 x64 | Controller or host desktop client |
| `client_ubuntu` | Ubuntu desktop x64 | Controller or host desktop client |

Start the server first. Then start one client as host and the other as controller with the same session code and server addresses.

The server only handles signaling and STUN. Successful screen and control sessions are direct client-to-client WebRTC connections. Relay candidates are disabled, so difficult NAT pairs can fail instead of sending session data through the server.
