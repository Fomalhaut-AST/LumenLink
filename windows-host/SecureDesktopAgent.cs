using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace LumenLink.WindowsHost;

internal static class SecureDesktopAgent
{
    private const uint DesktopAllAccess = 0x01FF;

    public static int Run(uint sessionId, string userSid)
    {
        using var protocol = SharedDesktopProtocol.Create(sessionId, userSid);
        using var shutdown = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            shutdown.Cancel();
        };

        EventLogWriter.Info($"secure_desktop.agent_started session={sessionId}");
        using var audioStop = CancellationTokenSource.CreateLinkedTokenSource(shutdown.Token);
        var audioThread = new Thread(() => WasapiLoopbackCapture.RunWithRetry(protocol, audioStop.Token))
        {
            IsBackground = true,
            Name = "lumenlink-wasapi-loopback"
        };
        audioThread.Start();
        string? activeDesktop = null;
        Thread? worker = null;
        CancellationTokenSource? workerStop = null;

        try
        {
            while (!shutdown.IsCancellationRequested)
            {
                string? nextDesktop = GetInputDesktopName();
                if (!string.Equals(activeDesktop, nextDesktop, StringComparison.OrdinalIgnoreCase)
                    || worker == null || !worker.IsAlive)
                {
                    workerStop?.Cancel();
                    worker?.Join(TimeSpan.FromSeconds(2));
                    workerStop?.Dispose();
                    workerStop = CancellationTokenSource.CreateLinkedTokenSource(shutdown.Token);
                    activeDesktop = nextDesktop;
                    if (nextDesktop == null)
                    {
                        protocol.MarkState(SharedDesktopProtocol.DesktopUnavailable);
                        worker = null;
                    }
                    else
                    {
                        string desktopForWorker = nextDesktop;
                        CancellationToken token = workerStop.Token;
                        worker = new Thread(() => CaptureAndInputLoop(protocol, sessionId, desktopForWorker, token))
                        {
                            IsBackground = true,
                            Name = "lumenlink-secure-desktop-capture"
                        };
                        worker.Start();
                    }
                }
                protocol.WriteInt64(SharedDesktopProtocol.OffsetAgentHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                shutdown.Token.WaitHandle.WaitOne(250);
            }
        }
        finally
        {
            audioStop.Cancel();
            audioThread.Join(TimeSpan.FromSeconds(3));
            workerStop?.Cancel();
            worker?.Join(TimeSpan.FromSeconds(2));
            workerStop?.Dispose();
            protocol.MarkState(SharedDesktopProtocol.DesktopUnavailable);
            EventLogWriter.Info($"secure_desktop.agent_stopped session={sessionId}");
        }
        return 0;
    }

    private static void CaptureAndInputLoop(SharedDesktopProtocol protocol, uint sessionId, string desktopName, CancellationToken stop)
    {
        IntPtr originalDesktop = GetThreadDesktop(GetCurrentThreadId());
        IntPtr desktop = OpenDesktop(desktopName, 0, false, DesktopAllAccess);
        if (desktop == IntPtr.Zero)
        {
            protocol.MarkState(SharedDesktopProtocol.DesktopUnavailable);
            return;
        }

        try
        {
            if (!SetThreadDesktop(desktop))
            {
                protocol.MarkState(SharedDesktopProtocol.DesktopUnavailable);
                return;
            }

            using var capture = new GdiDesktopCapture();
            while (!stop.IsCancellationRequested)
            {
                bool defaultDesktop = desktopName.Equals("Default", StringComparison.OrdinalIgnoreCase);
                int desktopState = defaultDesktop
                    ? SharedDesktopProtocol.DesktopDefault
                    : IsLockScreenActive(sessionId)
                        ? SharedDesktopProtocol.DesktopLockScreen
                        : SharedDesktopProtocol.DesktopProtected;
                protocol.MarkState(desktopState);
                ProcessInputs(protocol, desktopState);

                bool videoRequested = protocol.ReadInt32(SharedDesktopProtocol.OffsetVideoRequested) == 1;
                int fps = videoRequested
                    ? Math.Clamp(protocol.ReadInt32(SharedDesktopProtocol.OffsetDesiredFps), 1, 60)
                    : 4;
                int desiredWidth = protocol.ReadInt32(SharedDesktopProtocol.OffsetDesiredWidth);
                int desiredHeight = protocol.ReadInt32(SharedDesktopProtocol.OffsetDesiredHeight);
                if (desktopState == SharedDesktopProtocol.DesktopLockScreen
                    && videoRequested
                    && capture.Capture(desiredWidth, desiredHeight, out IntPtr pixels, out int width, out int height, out int stride))
                {
                    protocol.PublishFrame(pixels, width, height, stride, desktopState);
                }
                stop.WaitHandle.WaitOne(Math.Max(1, 1000 / fps));
            }
        }
        catch (Exception error)
        {
            EventLogWriter.Error($"secure_desktop.agent_worker_failed desktop={desktopName}", error);
            protocol.MarkState(SharedDesktopProtocol.DesktopUnavailable);
        }
        finally
        {
            if (originalDesktop != IntPtr.Zero) SetThreadDesktop(originalDesktop);
            CloseDesktop(desktop);
        }
    }

    private static void ProcessInputs(SharedDesktopProtocol protocol, int desktopState)
    {
        foreach (SharedDesktopProtocol.InputRecord input in protocol.ReadPendingInputs())
        {
            if (input.Type == SharedDesktopProtocol.InputSecureAttention)
            {
                if (desktopState == SharedDesktopProtocol.DesktopLockScreen)
                {
                    protocol.WriteInt64(SharedDesktopProtocol.OffsetSasRequestSequence, input.Sequence);
                }
                continue;
            }
            if (desktopState == SharedDesktopProtocol.DesktopProtected) continue;

            switch (input.Type)
            {
                case SharedDesktopProtocol.InputMouseMove:
                    SendAbsoluteMove(input.X, input.Y);
                    break;
                case SharedDesktopProtocol.InputMouseButton:
                    SendAbsoluteMove(input.X, input.Y);
                    SendMouseButton(input.Button, input.Action == 1);
                    break;
                case SharedDesktopProtocol.InputMouseWheel:
                    SendAbsoluteMove(input.X, input.Y);
                    SendMouseWheel(input.Delta);
                    break;
                case SharedDesktopProtocol.InputKeyboard:
                    SendKeyboard(input.Key, input.Action == 1);
                    break;
            }
        }
    }

    private static void SendAbsoluteMove(double x, double y)
    {
        var input = Input.Mouse(
            (int)Math.Round(Math.Clamp(x, 0, 1) * 65535),
            (int)Math.Round(Math.Clamp(y, 0, 1) * 65535),
            0,
            MouseEventAbsolute | MouseEventMove);
        SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
    }

    private static void SendMouseButton(int button, bool pressed)
    {
        uint flags = button switch
        {
            2 => pressed ? MouseEventRightDown : MouseEventRightUp,
            3 => pressed ? MouseEventMiddleDown : MouseEventMiddleUp,
            _ => pressed ? MouseEventLeftDown : MouseEventLeftUp
        };
        var input = Input.Mouse(0, 0, 0, flags);
        SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
    }

    private static void SendMouseWheel(double delta)
    {
        int amount = (int)Math.Round(-delta * 120.0);
        if (amount == 0) return;
        var input = Input.Mouse(0, 0, unchecked((uint)amount), MouseEventWheel);
        SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
    }

    private static void SendKeyboard(int virtualKey, bool pressed)
    {
        if (virtualKey <= 0 || virtualKey > ushort.MaxValue) return;
        var input = Input.Keyboard((ushort)virtualKey, pressed ? 0u : KeyEventKeyUp);
        SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
    }

    private static bool IsLockScreenActive(uint sessionId)
    {
        try
        {
            Process[] processes = Process.GetProcessesByName("LogonUI");
            try
            {
                foreach (Process process in processes)
                {
                    try { if (process.SessionId == sessionId) return true; }
                    catch { }
                }
                return false;
            }
            finally
            {
                foreach (Process process in processes) process.Dispose();
            }
        }
        catch
        {
            return false;
        }
    }

    private static string? GetInputDesktopName()
    {
        IntPtr desktop = OpenInputDesktop(0, false, DesktopAllAccess);
        if (desktop == IntPtr.Zero) return null;
        try
        {
            var name = new StringBuilder(256);
            return GetUserObjectInformation(desktop, 2, name, name.Capacity * sizeof(char), out _)
                ? name.ToString()
                : null;
        }
        finally
        {
            CloseDesktop(desktop);
        }
    }

    private const uint MouseEventMove = 0x0001;
    private const uint MouseEventLeftDown = 0x0002;
    private const uint MouseEventLeftUp = 0x0004;
    private const uint MouseEventRightDown = 0x0008;
    private const uint MouseEventRightUp = 0x0010;
    private const uint MouseEventMiddleDown = 0x0020;
    private const uint MouseEventMiddleUp = 0x0040;
    private const uint MouseEventWheel = 0x0800;
    private const uint MouseEventAbsolute = 0x8000;
    private const uint KeyEventKeyUp = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public InputUnion Data;

        public static Input Mouse(int x, int y, uint data, uint flags) => new()
        {
            Type = 0,
            Data = new InputUnion { Mouse = new MouseInput { X = x, Y = y, MouseData = data, Flags = flags } }
        };

        public static Input Keyboard(ushort key, uint flags) => new()
        {
            Type = 1,
            Data = new InputUnion { Keyboard = new KeyboardInput { VirtualKey = key, Flags = flags } }
        };
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int X;
        public int Y;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenDesktop(string desktop, uint flags, bool inherit, uint desiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint desiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetThreadDesktop(IntPtr desktop);

    [DllImport("user32.dll")]
    private static extern IntPtr GetThreadDesktop(uint threadId);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    private static extern bool CloseDesktop(IntPtr desktop);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder information, int length, out int needed);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint inputCount, Input[] inputs, int inputSize);
}

internal sealed class GdiDesktopCapture : IDisposable
{
    private const int DibRgbColors = 0;
    private const int BiRgb = 0;
    private const int Srccopy = 0x00CC0020;
    private const int CaptureBlt = 0x40000000;
    private const int Halftone = 4;

    private IntPtr screenDc;
    private IntPtr memoryDc;
    private IntPtr bitmap;
    private IntPtr previousBitmap;
    private IntPtr pixels;
    private int width;
    private int height;

    public bool Capture(int requestedWidth, int requestedHeight, out IntPtr outputPixels, out int outputWidth, out int outputHeight, out int stride)
    {
        int sourceWidth = GetSystemMetrics(0);
        int sourceHeight = GetSystemMetrics(1);
        int targetWidth = requestedWidth > 0 ? Math.Min(requestedWidth, 3840) : Math.Min(sourceWidth, 3840);
        int targetHeight = requestedHeight > 0 ? Math.Min(requestedHeight, 2160) : Math.Min(sourceHeight, 2160);
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0)
        {
            outputPixels = IntPtr.Zero;
            outputWidth = outputHeight = stride = 0;
            return false;
        }

        EnsureResources(targetWidth, targetHeight);
        SetStretchBltMode(memoryDc, Halftone);
        bool copied = StretchBlt(memoryDc, 0, 0, targetWidth, targetHeight,
            screenDc, 0, 0, sourceWidth, sourceHeight, Srccopy | CaptureBlt);
        outputPixels = pixels;
        outputWidth = targetWidth;
        outputHeight = targetHeight;
        stride = targetWidth * 4;
        return copied;
    }

    public void Dispose()
    {
        if (memoryDc != IntPtr.Zero && previousBitmap != IntPtr.Zero) SelectObject(memoryDc, previousBitmap);
        if (bitmap != IntPtr.Zero) DeleteObject(bitmap);
        if (memoryDc != IntPtr.Zero) DeleteDC(memoryDc);
        if (screenDc != IntPtr.Zero) ReleaseDC(IntPtr.Zero, screenDc);
        bitmap = previousBitmap = memoryDc = screenDc = pixels = IntPtr.Zero;
    }

    private void EnsureResources(int targetWidth, int targetHeight)
    {
        if (screenDc != IntPtr.Zero && width == targetWidth && height == targetHeight) return;
        Dispose();
        screenDc = GetDC(IntPtr.Zero);
        if (screenDc == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "GetDC failed");
        memoryDc = CreateCompatibleDC(screenDc);
        if (memoryDc == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateCompatibleDC failed");
        var info = new BitmapInfo
        {
            Header = new BitmapInfoHeader
            {
                Size = (uint)Marshal.SizeOf<BitmapInfoHeader>(),
                Width = targetWidth,
                Height = -targetHeight,
                Planes = 1,
                BitCount = 32,
                Compression = BiRgb
            }
        };
        bitmap = CreateDIBSection(screenDc, ref info, DibRgbColors, out pixels, IntPtr.Zero, 0);
        if (bitmap == IntPtr.Zero || pixels == IntPtr.Zero)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateDIBSection failed");
        previousBitmap = SelectObject(memoryDc, bitmap);
        width = targetWidth;
        height = targetHeight;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfoHeader
    {
        public uint Size;
        public int Width;
        public int Height;
        public ushort Planes;
        public ushort BitCount;
        public uint Compression;
        public uint SizeImage;
        public int XPelsPerMeter;
        public int YPelsPerMeter;
        public uint ColorsUsed;
        public uint ColorsImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfo
    {
        public BitmapInfoHeader Header;
        public uint Colors;
    }

    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr window, IntPtr dc);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr value);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr value);
    [DllImport("gdi32.dll")] private static extern int SetStretchBltMode(IntPtr dc, int mode);
    [DllImport("gdi32.dll")] private static extern bool StretchBlt(IntPtr destination, int x, int y, int width, int height,
        IntPtr source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, int operation);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateDIBSection(IntPtr dc, ref BitmapInfo info, uint usage,
        out IntPtr bits, IntPtr section, uint offset);
}
