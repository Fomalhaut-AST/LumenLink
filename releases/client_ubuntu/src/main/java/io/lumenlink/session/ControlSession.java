package io.lumenlink.session;

/** Active or pending control session between two devices. */
public final class ControlSession {
    public enum Role { CONTROLLER, HOST }
    public enum State { REQUESTING, INCOMING, CONNECTING, CONNECTED, ENDED }

    private final String sessionId;
    private final String peerDeviceId;
    private final String peerDisplayName;
    private final Role role;
    private volatile State state;

    public ControlSession(String sessionId, String peerDeviceId, String peerDisplayName, Role role, State state) {
        this.sessionId = sessionId;
        this.peerDeviceId = peerDeviceId;
        this.peerDisplayName = peerDisplayName;
        this.role = role;
        this.state = state;
    }

    public String sessionId() { return sessionId; }
    public String peerDeviceId() { return peerDeviceId; }
    public String peerDisplayName() { return peerDisplayName; }
    public Role role() { return role; }
    public State state() { return state; }
    public void setState(State state) { this.state = state; }
    public boolean isController() { return role == Role.CONTROLLER; }
}
