package io.lumenlink.windows;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import io.lumenlink.control.RemoteControlEvent;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Memory-only IPC with the LocalSystem secure-desktop host. */
public final class SecureDesktopBridge implements AutoCloseable {
    private static final int MAGIC = 0x4C4C5344;
    private static final int VERSION = 3;
    private static final int CAPACITY = 80 * 1024 * 1024;
    private static final int FILE_MAP_ALL_ACCESS = 0x000F001F;
    private static final int INPUT_OFFSET = 4096;
    private static final int INPUT_SLOT_SIZE = 64;
    private static final int INPUT_SLOT_COUNT = 256;
    private static final int AUDIO_OFFSET = 64 * 1024;
    private static final int AUDIO_SLOT_SIZE = 8192;
    private static final int AUDIO_SLOT_COUNT = 32;
    private static final int FRAME_OFFSET = 512 * 1024;
    private static final int MAX_FRAME_BYTES = 3840 * 2160 * 4;

    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_DESKTOP_STATE = 8;
    private static final int OFFSET_WIDTH = 12;
    private static final int OFFSET_HEIGHT = 16;
    private static final int OFFSET_STRIDE = 20;
    private static final int OFFSET_ACTIVE_BUFFER = 24;
    private static final int OFFSET_DESIRED_WIDTH = 28;
    private static final int OFFSET_DESIRED_HEIGHT = 32;
    private static final int OFFSET_DESIRED_FPS = 36;
    private static final int OFFSET_FRAME_SEQUENCE = 40;
    private static final int OFFSET_INPUT_WRITE_SEQUENCE = 48;
    private static final int OFFSET_AGENT_HEARTBEAT = 64;
    private static final int OFFSET_AUDIO_WRITE_SEQUENCE = 88;
    private static final int OFFSET_AUDIO_READ_SEQUENCE = 96;
    private static final int OFFSET_AUDIO_STATE = 104;
    private static final int OFFSET_AUDIO_HEARTBEAT = 112;
    private static final int OFFSET_AUDIO_REQUESTED = 120;
    private static final int OFFSET_VIDEO_REQUESTED = 124;

    private static final int DESKTOP_LOCK_SCREEN = 2;
    private static final int INPUT_MOUSE_MOVE = 1;
    private static final int INPUT_MOUSE_BUTTON = 2;
    private static final int INPUT_MOUSE_WHEEL = 3;
    private static final int INPUT_KEYBOARD = 4;
    private static final int INPUT_SECURE_ATTENTION = 5;

    private final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private final String mappingName;
    private HANDLE mapping;
    private Pointer view;
    private long nextConnectAttemptMillis;
    private int desiredWidth;
    private int desiredHeight;
    private int desiredFps = 30;
    private boolean audioRequested;
    private boolean videoRequested;

    public SecureDesktopBridge() {
        mappingName = windows ? "Global\\LumenLinkSecureDesktop-" + currentSessionId() : "";
    }

    public synchronized void configure(int width, int height, int fps) {
        desiredWidth = Math.max(0, Math.min(3840, width));
        desiredHeight = Math.max(0, Math.min(2160, height));
        desiredFps = Math.max(1, Math.min(60, fps));
        if (ensureConnected()) writeConfiguration();
    }

    public synchronized boolean isAvailable() {
        if (!ensureConnected()) return false;
        long heartbeat = view.getLong(OFFSET_AGENT_HEARTBEAT);
        return heartbeat > 0 && Math.abs(System.currentTimeMillis() - heartbeat) <= 5_000;
    }

    public synchronized boolean isLockScreenActive() {
        return isAvailable() && view.getInt(OFFSET_DESKTOP_STATE) == DESKTOP_LOCK_SCREEN;
    }

    public synchronized boolean isAudioAvailable() {
        if (!isAvailable() || view.getInt(OFFSET_AUDIO_STATE) != 1) return false;
        long heartbeat = view.getLong(OFFSET_AUDIO_HEARTBEAT);
        return heartbeat > 0 && Math.abs(System.currentTimeMillis() - heartbeat) <= 5_000;
    }

    public synchronized void setAudioRequested(boolean requested) {
        audioRequested = requested;
        if (ensureConnected()) view.setInt(OFFSET_AUDIO_REQUESTED, requested ? 1 : 0);
    }

    public synchronized void setVideoRequested(boolean requested) {
        videoRequested = requested;
        if (ensureConnected()) view.setInt(OFFSET_VIDEO_REQUESTED, requested ? 1 : 0);
    }

    public synchronized SecureFrame readFrame(long afterSequence) {
        if (!isLockScreenActive()) return null;
        long sequence = view.getLong(OFFSET_FRAME_SEQUENCE);
        if (sequence <= afterSequence) return null;
        VarHandle.acquireFence();
        int width = view.getInt(OFFSET_WIDTH);
        int height = view.getInt(OFFSET_HEIGHT);
        int stride = view.getInt(OFFSET_STRIDE);
        int activeBuffer = view.getInt(OFFSET_ACTIVE_BUFFER);
        long bytes = (long) stride * height;
        if (width <= 0 || height <= 0 || stride != width * 4 || bytes <= 0 || bytes > MAX_FRAME_BYTES
                || (activeBuffer != 0 && activeBuffer != 1)) {
            return null;
        }
        long offset = FRAME_OFFSET + (long) activeBuffer * MAX_FRAME_BYTES;
        ByteBuffer bgra = view.getByteBuffer(offset, bytes);
        if (view.getLong(OFFSET_FRAME_SEQUENCE) != sequence) return null;
        return new SecureFrame(sequence, width, height, stride, bgra);
    }

    public synchronized AudioFrame readAudio(long afterSequence) {
        if (!isAudioAvailable()) return null;
        long writeSequence = view.getLong(OFFSET_AUDIO_WRITE_SEQUENCE);
        if (writeSequence <= afterSequence) return null;
        long sequence = afterSequence + 1;
        if (writeSequence - afterSequence > AUDIO_SLOT_COUNT) sequence = writeSequence - AUDIO_SLOT_COUNT + 1;
        int offset = AUDIO_OFFSET + (int) (sequence % AUDIO_SLOT_COUNT) * AUDIO_SLOT_SIZE;
        VarHandle.acquireFence();
        if (view.getLong(offset) != sequence) return null;
        int length = view.getInt(offset + 8L);
        int bitsPerSample = view.getInt(offset + 12L);
        int sampleRate = view.getInt(offset + 16L);
        int channels = view.getInt(offset + 20L);
        int frames = view.getInt(offset + 24L);
        long expected = (long) frames * channels * bitsPerSample / 8;
        if (length <= 0 || length > AUDIO_SLOT_SIZE - 32 || bitsPerSample != 16
                || sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 8
                || frames <= 0 || expected != length) {
            return null;
        }
        byte[] pcm = view.getByteArray(offset + 32L, length);
        if (view.getLong(offset) != sequence) return null;
        view.setLong(OFFSET_AUDIO_READ_SEQUENCE, sequence);
        return new AudioFrame(sequence, pcm, bitsPerSample, sampleRate, channels, frames);
    }

    public synchronized boolean sendInput(RemoteControlEvent event) {
        if (event == null || !isLockScreenActive()) return false;
        int type;
        int action = event.action() == RemoteControlEvent.Action.PRESS ? 1 : 2;
        int key = 0;
        int button = 0;
        switch (event.type()) {
            case MOUSE_MOVE -> type = INPUT_MOUSE_MOVE;
            case MOUSE_BUTTON -> {
                type = INPUT_MOUSE_BUTTON;
                button = switch (event.keyOrButton().toUpperCase(Locale.ROOT)) {
                    case "RIGHT" -> 2;
                    case "MIDDLE" -> 3;
                    default -> 1;
                };
            }
            case MOUSE_SCROLL -> type = INPUT_MOUSE_WHEEL;
            case KEY -> {
                type = INPUT_KEYBOARD;
                key = windowsVirtualKey(event.keyOrButton());
                if (key == 0) return true;
            }
            case SECURE_ATTENTION -> type = INPUT_SECURE_ATTENTION;
            default -> { return false; }
        }

        long sequence = view.getLong(OFFSET_INPUT_WRITE_SEQUENCE) + 1;
        int offset = INPUT_OFFSET + (int) (sequence % INPUT_SLOT_COUNT) * INPUT_SLOT_SIZE;
        view.setInt(offset + 8L, type);
        view.setInt(offset + 12L, action);
        view.setDouble(offset + 16L, event.x());
        view.setDouble(offset + 24L, event.y());
        view.setDouble(offset + 32L, event.delta());
        view.setInt(offset + 40L, key);
        view.setInt(offset + 44L, button);
        VarHandle.releaseFence();
        view.setLong(offset, sequence);
        view.setLong(OFFSET_INPUT_WRITE_SEQUENCE, sequence);
        return true;
    }

    @Override
    public synchronized void close() {
        disconnect();
    }

    private boolean ensureConnected() {
        if (!windows) return false;
        if (view != null) {
            if (view.getInt(OFFSET_MAGIC) == MAGIC && view.getInt(OFFSET_VERSION) == VERSION) return true;
            disconnect();
        }
        long now = System.currentTimeMillis();
        if (now < nextConnectAttemptMillis) return false;
        nextConnectAttemptMillis = now + 1_000;
        HANDLE opened = Kernel32.INSTANCE.OpenFileMapping(FILE_MAP_ALL_ACCESS, false, mappingName);
        if (opened == null) return false;
        Pointer mapped = Kernel32.INSTANCE.MapViewOfFile(opened, FILE_MAP_ALL_ACCESS, 0, 0, CAPACITY);
        if (mapped == null) {
            Kernel32.INSTANCE.CloseHandle(opened);
            return false;
        }
        mapping = opened;
        view = mapped;
        if (view.getInt(OFFSET_MAGIC) != MAGIC || view.getInt(OFFSET_VERSION) != VERSION) {
            disconnect();
            return false;
        }
        writeConfiguration();
        return true;
    }

    private void writeConfiguration() {
        view.setInt(OFFSET_DESIRED_WIDTH, desiredWidth);
        view.setInt(OFFSET_DESIRED_HEIGHT, desiredHeight);
        view.setInt(OFFSET_DESIRED_FPS, desiredFps);
        view.setInt(OFFSET_AUDIO_REQUESTED, audioRequested ? 1 : 0);
        view.setInt(OFFSET_VIDEO_REQUESTED, videoRequested ? 1 : 0);
    }

    private void disconnect() {
        if (view != null) {
            Kernel32.INSTANCE.UnmapViewOfFile(view);
            view = null;
        }
        if (mapping != null) {
            Kernel32.INSTANCE.CloseHandle(mapping);
            mapping = null;
        }
    }

    private static int currentSessionId() {
        IntByReference session = new IntByReference();
        if (!Kernel32.INSTANCE.ProcessIdToSessionId(Kernel32.INSTANCE.GetCurrentProcessId(), session)) {
            throw new IllegalStateException("Unable to determine the current Windows session");
        }
        return session.getValue();
    }

    static int windowsVirtualKey(String key) {
        if (key == null || key.isBlank()) return 0;
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() == 1) {
            char ch = normalized.charAt(0);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) return ch;
        }
        return switch (normalized) {
            case "ENTER" -> 0x0D;
            case "TAB" -> 0x09;
            case "ESC", "ESCAPE" -> 0x1B;
            case "BACK_SPACE", "BACKSPACE" -> 0x08;
            case "SPACE" -> 0x20;
            case "DELETE" -> 0x2E;
            case "UP" -> 0x26;
            case "DOWN" -> 0x28;
            case "LEFT" -> 0x25;
            case "RIGHT" -> 0x27;
            case "HOME" -> 0x24;
            case "END" -> 0x23;
            case "PAGE_UP" -> 0x21;
            case "PAGE_DOWN" -> 0x22;
            case "SHIFT" -> 0x10;
            case "CONTROL", "CTRL" -> 0x11;
            case "ALT" -> 0x12;
            case "WINDOWS", "META" -> 0x5B;
            case "CAPS_LOCK" -> 0x14;
            case "MINUS" -> 0xBD;
            case "EQUALS" -> 0xBB;
            case "OPEN_BRACKET" -> 0xDB;
            case "CLOSE_BRACKET" -> 0xDD;
            case "BACK_SLASH" -> 0xDC;
            case "SEMICOLON" -> 0xBA;
            case "QUOTE" -> 0xDE;
            case "COMMA" -> 0xBC;
            case "PERIOD" -> 0xBE;
            case "SLASH" -> 0xBF;
            case "BACK_QUOTE" -> 0xC0;
            case "NUMPAD0" -> 0x60;
            case "NUMPAD1" -> 0x61;
            case "NUMPAD2" -> 0x62;
            case "NUMPAD3" -> 0x63;
            case "NUMPAD4" -> 0x64;
            case "NUMPAD5" -> 0x65;
            case "NUMPAD6" -> 0x66;
            case "NUMPAD7" -> 0x67;
            case "NUMPAD8" -> 0x68;
            case "NUMPAD9" -> 0x69;
            case "MULTIPLY" -> 0x6A;
            case "ADD" -> 0x6B;
            case "SUBTRACT" -> 0x6D;
            case "DECIMAL" -> 0x6E;
            case "DIVIDE" -> 0x6F;
            default -> functionKey(normalized);
        };
    }

    private static int functionKey(String value) {
        if (!value.startsWith("F") || value.length() > 3) return 0;
        try {
            int index = Integer.parseInt(value.substring(1));
            return index >= 1 && index <= 12 ? 0x70 + index - 1 : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record SecureFrame(long sequence, int width, int height, int stride, ByteBuffer bgra) { }
    public record AudioFrame(long sequence, byte[] pcm, int bitsPerSample, int sampleRate, int channels, int frames) { }
}
