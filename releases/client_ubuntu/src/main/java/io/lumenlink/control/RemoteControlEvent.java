package io.lumenlink.control;

import java.util.Objects;

/** A compact normalized event sent only through an authenticated WebRTC DataChannel. */
public record RemoteControlEvent(Type type, Action action, double x, double y, String keyOrButton) {
    public enum Type { MOUSE_MOVE, MOUSE_BUTTON, MOUSE_SCROLL, KEY }
    public enum Action { PRESS, RELEASE, MOVE, SCROLL }

    public RemoteControlEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        keyOrButton = keyOrButton == null ? "" : keyOrButton;
    }

    public static RemoteControlEvent mouseMove(double x, double y) {
        if (x < 0 || x > 1 || y < 0 || y > 1) throw new IllegalArgumentException("coordinates must be in [0, 1]");
        return new RemoteControlEvent(Type.MOUSE_MOVE, Action.MOVE, x, y, "");
    }
}
