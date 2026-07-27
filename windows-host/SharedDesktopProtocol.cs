using System.ComponentModel;
using System.Runtime.InteropServices;

namespace LumenLink.WindowsHost;

internal sealed class SharedDesktopProtocol : IDisposable
{
    public const int Magic = 0x4C4C5344;
    public const int Version = 3;
    public const long Capacity = 80L * 1024 * 1024;
    public const int HeaderSize = 4096;
    public const int InputOffset = HeaderSize;
    public const int InputSlotSize = 64;
    public const int InputSlotCount = 256;
    public const int AudioOffset = 64 * 1024;
    public const int AudioSlotSize = 8192;
    public const int AudioSlotCount = 32;
    public const int FrameOffset = 512 * 1024;
    public const int MaxFrameBytes = 3840 * 2160 * 4;

    public const int OffsetMagic = 0;
    public const int OffsetVersion = 4;
    public const int OffsetDesktopState = 8;
    public const int OffsetWidth = 12;
    public const int OffsetHeight = 16;
    public const int OffsetStride = 20;
    public const int OffsetActiveBuffer = 24;
    public const int OffsetDesiredWidth = 28;
    public const int OffsetDesiredHeight = 32;
    public const int OffsetDesiredFps = 36;
    public const int OffsetFrameSequence = 40;
    public const int OffsetInputWriteSequence = 48;
    public const int OffsetInputReadSequence = 56;
    public const int OffsetAgentHeartbeat = 64;
    public const int OffsetSasRequestSequence = 72;
    public const int OffsetSasHandledSequence = 80;
    public const int OffsetAudioWriteSequence = 88;
    public const int OffsetAudioReadSequence = 96;
    public const int OffsetAudioState = 104;
    public const int OffsetAudioHeartbeat = 112;
    public const int OffsetAudioRequested = 120;
    public const int OffsetVideoRequested = 124;

    public const int DesktopUnavailable = 0;
    public const int DesktopDefault = 1;
    public const int DesktopLockScreen = 2;
    public const int DesktopProtected = 3;

    public const int InputMouseMove = 1;
    public const int InputMouseButton = 2;
    public const int InputMouseWheel = 3;
    public const int InputKeyboard = 4;
    public const int InputSecureAttention = 5;

    private const uint PageReadWrite = 0x04;
    private const uint FileMapAllAccess = 0x000F001F;
    private static readonly IntPtr InvalidHandleValue = new(-1);

    private readonly IntPtr mapping;
    private readonly IntPtr view;
    private IntPtr securityDescriptor;

    private SharedDesktopProtocol(IntPtr mapping, IntPtr view, IntPtr securityDescriptor)
    {
        this.mapping = mapping;
        this.view = view;
        this.securityDescriptor = securityDescriptor;
    }

    public static string MappingName(uint sessionId) => $"Global\\LumenLinkSecureDesktop-{sessionId}";

    public static SharedDesktopProtocol Create(uint sessionId, string userSid)
    {
        string sddl = $"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;{userSid})";
        if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, out IntPtr descriptor, out _))
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not create secure desktop IPC ACL");
        }

        var attributes = new SecurityAttributes
        {
            Length = Marshal.SizeOf<SecurityAttributes>(),
            SecurityDescriptor = descriptor,
            InheritHandle = false
        };
        SplitCapacity(out uint high, out uint low);
        IntPtr mapping = CreateFileMapping(InvalidHandleValue, ref attributes, PageReadWrite, high, low, MappingName(sessionId));
        if (mapping == IntPtr.Zero)
        {
            LocalFree(descriptor);
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not create secure desktop IPC mapping");
        }
        IntPtr view = MapViewOfFile(mapping, FileMapAllAccess, 0, 0, new UIntPtr((ulong) Capacity));
        if (view == IntPtr.Zero)
        {
            CloseHandle(mapping);
            LocalFree(descriptor);
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not map secure desktop IPC memory");
        }

        var protocol = new SharedDesktopProtocol(mapping, view, descriptor);
        protocol.Initialize();
        return protocol;
    }

    public static SharedDesktopProtocol? Open(uint sessionId)
    {
        IntPtr mapping = OpenFileMapping(FileMapAllAccess, false, MappingName(sessionId));
        if (mapping == IntPtr.Zero) return null;
        IntPtr view = MapViewOfFile(mapping, FileMapAllAccess, 0, 0, new UIntPtr((ulong) Capacity));
        if (view == IntPtr.Zero)
        {
            CloseHandle(mapping);
            return null;
        }
        return new SharedDesktopProtocol(mapping, view, IntPtr.Zero);
    }

    public int ReadInt32(int offset) => Marshal.ReadInt32(view, offset);
    public long ReadInt64(int offset) => Marshal.ReadInt64(view, offset);
    public double ReadDouble(int offset) => BitConverter.Int64BitsToDouble(ReadInt64(offset));
    public void WriteInt32(int offset, int value) => Marshal.WriteInt32(view, offset, value);
    public void WriteInt64(int offset, long value) => Marshal.WriteInt64(view, offset, value);

    public void PublishFrame(IntPtr source, int width, int height, int stride, int desktopState)
    {
        int bytes = checked(stride * height);
        if (bytes <= 0 || bytes > MaxFrameBytes) return;
        int nextBuffer = ReadInt32(OffsetActiveBuffer) == 0 ? 1 : 0;
        IntPtr destination = IntPtr.Add(view, FrameOffset + nextBuffer * MaxFrameBytes);
        CopyMemory(destination, source, (UIntPtr)(uint) bytes);
        Thread.MemoryBarrier();
        WriteInt32(OffsetWidth, width);
        WriteInt32(OffsetHeight, height);
        WriteInt32(OffsetStride, stride);
        WriteInt32(OffsetDesktopState, desktopState);
        WriteInt32(OffsetActiveBuffer, nextBuffer);
        WriteInt64(OffsetAgentHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        Thread.MemoryBarrier();
        WriteInt64(OffsetFrameSequence, ReadInt64(OffsetFrameSequence) + 1);
    }

    public void MarkState(int state)
    {
        WriteInt32(OffsetDesktopState, state);
        WriteInt64(OffsetAgentHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public void PublishAudio(byte[] pcm, int bitsPerSample, int sampleRate, int channels, int frames)
    {
        if (pcm == null || pcm.Length == 0 || pcm.Length > AudioSlotSize - 32) return;
        long sequence = ReadInt64(OffsetAudioWriteSequence) + 1;
        int offset = AudioOffset + (int)(sequence % AudioSlotCount) * AudioSlotSize;
        Marshal.Copy(pcm, 0, IntPtr.Add(view, offset + 32), pcm.Length);
        WriteInt32(offset + 8, pcm.Length);
        WriteInt32(offset + 12, bitsPerSample);
        WriteInt32(offset + 16, sampleRate);
        WriteInt32(offset + 20, channels);
        WriteInt32(offset + 24, frames);
        Thread.MemoryBarrier();
        WriteInt64(offset, sequence);
        WriteInt32(OffsetAudioState, 1);
        WriteInt64(OffsetAudioHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        WriteInt64(OffsetAudioWriteSequence, sequence);
    }

    public void MarkAudioUnavailable()
    {
        WriteInt32(OffsetAudioState, 0);
        WriteInt64(OffsetAudioHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public void MarkAudioAvailable()
    {
        WriteInt32(OffsetAudioState, 1);
        WriteInt64(OffsetAudioHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public IEnumerable<InputRecord> ReadPendingInputs()
    {
        long read = ReadInt64(OffsetInputReadSequence);
        long write = ReadInt64(OffsetInputWriteSequence);
        if (write - read > InputSlotCount) read = write - InputSlotCount;
        while (read < write)
        {
            long sequence = read + 1;
            int offset = InputOffset + (int)(sequence % InputSlotCount) * InputSlotSize;
            Thread.MemoryBarrier();
            if (ReadInt64(offset) != sequence) break;
            yield return new InputRecord(
                sequence,
                ReadInt32(offset + 8),
                ReadInt32(offset + 12),
                ReadDouble(offset + 16),
                ReadDouble(offset + 24),
                ReadDouble(offset + 32),
                ReadInt32(offset + 40),
                ReadInt32(offset + 44));
            read = sequence;
            WriteInt64(OffsetInputReadSequence, read);
        }
    }

    public void Dispose()
    {
        if (view != IntPtr.Zero) UnmapViewOfFile(view);
        if (mapping != IntPtr.Zero) CloseHandle(mapping);
        if (securityDescriptor != IntPtr.Zero)
        {
            LocalFree(securityDescriptor);
            securityDescriptor = IntPtr.Zero;
        }
    }

    private void Initialize()
    {
        WriteInt32(OffsetMagic, Magic);
        WriteInt32(OffsetVersion, Version);
        WriteInt32(OffsetDesktopState, DesktopUnavailable);
        WriteInt32(OffsetDesiredFps, 30);
        WriteInt64(OffsetAgentHeartbeat, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    private static void SplitCapacity(out uint high, out uint low)
    {
        ulong value = (ulong) Capacity;
        high = (uint)(value >> 32);
        low = (uint)(value & uint.MaxValue);
    }

    public readonly record struct InputRecord(long Sequence, int Type, int Action, double X, double Y, double Delta, int Key, int Button);

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr SecurityDescriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateFileMapping(IntPtr file, ref SecurityAttributes attributes, uint protection,
        uint maximumSizeHigh, uint maximumSizeLow, string name);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr OpenFileMapping(uint desiredAccess, bool inheritHandle, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr MapViewOfFile(IntPtr mapping, uint desiredAccess, uint offsetHigh, uint offsetLow, UIntPtr bytesToMap);

    [DllImport("kernel32.dll")]
    private static extern bool UnmapViewOfFile(IntPtr address);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", EntryPoint = "RtlMoveMemory")]
    private static extern void CopyMemory(IntPtr destination, IntPtr source, UIntPtr length);

    [DllImport("advapi32.dll", EntryPoint = "ConvertStringSecurityDescriptorToSecurityDescriptorW",
        CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string stringSecurityDescriptor,
        uint stringSDRevision, out IntPtr securityDescriptor, out uint securityDescriptorSize);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr memory);
}
