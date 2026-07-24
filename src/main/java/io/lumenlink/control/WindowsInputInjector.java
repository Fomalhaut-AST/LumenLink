package io.lumenlink.control;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Map;

/** Applies remote control events on the local Windows desktop. */
public final class WindowsInputInjector {
    private final ControlPermissionGate gate;
    private final Robot robot;

    public WindowsInputInjector(ControlPermissionGate gate) {
        this.gate = gate;
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(0);
            this.robot.setAutoWaitForIdle(false);
        } catch (AWTException error) {
            throw new IllegalStateException("Unable to create input robot", error);
        }
    }

    public void inject(RemoteControlEvent event) {
        if (!gate.allows(event)) return;
        Rectangle bounds = primaryBounds();
        int x = bounds.x + (int) Math.round(event.x() * Math.max(1, bounds.width - 1));
        int y = bounds.y + (int) Math.round(event.y() * Math.max(1, bounds.height - 1));
        switch (event.type()) {
            case MOUSE_MOVE -> robot.mouseMove(x, y);
            case MOUSE_BUTTON -> {
                robot.mouseMove(x, y);
                int mask = buttonMask(event.keyOrButton());
                if (event.action() == RemoteControlEvent.Action.PRESS) robot.mousePress(mask);
                else if (event.action() == RemoteControlEvent.Action.RELEASE) robot.mouseRelease(mask);
            }
            case MOUSE_SCROLL -> {
                robot.mouseMove(x, y);
                int notches = (int) Math.round(event.delta());
                if (notches != 0) robot.mouseWheel(-notches);
            }
            case KEY -> {
                Integer keyCode = keyCode(event.keyOrButton());
                if (keyCode == null) return;
                if (event.action() == RemoteControlEvent.Action.PRESS) robot.keyPress(keyCode);
                else if (event.action() == RemoteControlEvent.Action.RELEASE) robot.keyRelease(keyCode);
            }
        }
    }

    private static Rectangle primaryBounds() {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return device.getDefaultConfiguration().getBounds();
    }

    private static int buttonMask(String button) {
        String value = button == null ? "LEFT" : button.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MIDDLE" -> InputEvent.BUTTON2_DOWN_MASK;
            case "RIGHT" -> InputEvent.BUTTON3_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    private static Integer keyCode(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() == 1) {
            char ch = normalized.charAt(0);
            if (ch >= 'A' && ch <= 'Z') return (int) ch;
            if (ch >= '0' && ch <= '9') return (int) ch;
        }
        Map<String, Integer> map = Map.ofEntries(
                Map.entry("ENTER", KeyEvent.VK_ENTER),
                Map.entry("TAB", KeyEvent.VK_TAB),
                Map.entry("ESC", KeyEvent.VK_ESCAPE),
                Map.entry("ESCAPE", KeyEvent.VK_ESCAPE),
                Map.entry("BACK_SPACE", KeyEvent.VK_BACK_SPACE),
                Map.entry("BACKSPACE", KeyEvent.VK_BACK_SPACE),
                Map.entry("SPACE", KeyEvent.VK_SPACE),
                Map.entry("DELETE", KeyEvent.VK_DELETE),
                Map.entry("UP", KeyEvent.VK_UP),
                Map.entry("DOWN", KeyEvent.VK_DOWN),
                Map.entry("LEFT", KeyEvent.VK_LEFT),
                Map.entry("RIGHT", KeyEvent.VK_RIGHT),
                Map.entry("HOME", KeyEvent.VK_HOME),
                Map.entry("END", KeyEvent.VK_END),
                Map.entry("PAGE_UP", KeyEvent.VK_PAGE_UP),
                Map.entry("PAGE_DOWN", KeyEvent.VK_PAGE_DOWN),
                Map.entry("SHIFT", KeyEvent.VK_SHIFT),
                Map.entry("CONTROL", KeyEvent.VK_CONTROL),
                Map.entry("CTRL", KeyEvent.VK_CONTROL),
                Map.entry("ALT", KeyEvent.VK_ALT),
                Map.entry("WINDOWS", KeyEvent.VK_WINDOWS),
                Map.entry("META", KeyEvent.VK_META)
        );
        Integer mapped = map.get(normalized);
        if (mapped != null) return mapped;
        if (normalized.startsWith("F") && normalized.length() <= 3) {
            try {
                int index = Integer.parseInt(normalized.substring(1));
                if (index >= 1 && index <= 12) return KeyEvent.VK_F1 + (index - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
