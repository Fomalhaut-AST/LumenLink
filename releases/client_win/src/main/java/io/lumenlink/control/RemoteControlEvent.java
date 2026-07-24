package io.lumenlink.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Normalized input event sent only through the WebRTC control DataChannel. */
public record RemoteControlEvent(Type type, Action action, double x, double y, double delta, String keyOrButton) {
    public enum Type { MOUSE_MOVE, MOUSE_BUTTON, MOUSE_SCROLL, KEY }
    public enum Action { PRESS, RELEASE, MOVE, SCROLL }

    public RemoteControlEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        keyOrButton = keyOrButton == null ? "" : keyOrButton;
    }

    public static RemoteControlEvent mouseMove(double x, double y) {
        return new RemoteControlEvent(Type.MOUSE_MOVE, Action.MOVE, clamp01(x), clamp01(y), 0, "");
    }

    public static RemoteControlEvent mouseButton(Action action, double x, double y, String button) {
        if (action != Action.PRESS && action != Action.RELEASE) {
            throw new IllegalArgumentException("mouse button action must be PRESS or RELEASE");
        }
        return new RemoteControlEvent(Type.MOUSE_BUTTON, action, clamp01(x), clamp01(y), 0, button == null ? "LEFT" : button);
    }

    public static RemoteControlEvent mouseScroll(double x, double y, double delta) {
        return new RemoteControlEvent(Type.MOUSE_SCROLL, Action.SCROLL, clamp01(x), clamp01(y), delta, "");
    }

    public static RemoteControlEvent key(Action action, String key) {
        if (action != Action.PRESS && action != Action.RELEASE) {
            throw new IllegalArgumentException("key action must be PRESS or RELEASE");
        }
        return new RemoteControlEvent(Type.KEY, action, 0, 0, 0, key == null ? "" : key);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        map.put("action", action.name());
        map.put("x", x);
        map.put("y", y);
        map.put("delta", delta);
        map.put("keyOrButton", keyOrButton);
        return map;
    }

    public static RemoteControlEvent fromMap(Map<String, Object> map) {
        Type type = Type.valueOf(String.valueOf(map.get("type")));
        Action action = Action.valueOf(String.valueOf(map.get("action")));
        double x = doubleValue(map.get("x"), 0);
        double y = doubleValue(map.get("y"), 0);
        double delta = doubleValue(map.get("delta"), 0);
        String key = map.get("keyOrButton") == null ? "" : String.valueOf(map.get("keyOrButton"));
        return new RemoteControlEvent(type, action, x, y, delta, key);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
