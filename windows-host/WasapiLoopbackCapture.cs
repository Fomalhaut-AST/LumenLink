using System.Runtime.InteropServices;

namespace LumenLink.WindowsHost;

internal static class WasapiLoopbackCapture
{
    private const int SampleRate = 48_000;
    private const int Channels = 2;
    private const int BitsPerSample = 16;
    private const int FramesPerChunk = 480;
    private const int BytesPerChunk = FramesPerChunk * Channels * BitsPerSample / 8;
    private const uint ClsctxAll = 23;
    private const uint StreamFlagsLoopback = 0x00020000;
    private const uint StreamFlagsAutoConvertPcm = 0x80000000;
    private const uint StreamFlagsSrcDefaultQuality = 0x08000000;
    private const uint BufferFlagsSilent = 0x2;
    private const int CoInitMultithreaded = 0;
    private static readonly Guid AudioClientIid = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid AudioCaptureClientIid = new("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    private static readonly Guid DeviceEnumeratorClsid = new("BCDE0395-E52F-467C-8E3D-C4579291692E");

    public static void RunWithRetry(SharedDesktopProtocol protocol, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            if (protocol.ReadInt32(SharedDesktopProtocol.OffsetAudioRequested) != 1)
            {
                protocol.MarkAudioUnavailable();
                stop.WaitHandle.WaitOne(250);
                continue;
            }
            try
            {
                Capture(protocol, stop, null, null);
            }
            catch (Exception error)
            {
                protocol.MarkAudioUnavailable();
                EventLogWriter.Error("audio.loopback_failed", error);
                stop.WaitHandle.WaitOne(TimeSpan.FromSeconds(2));
            }
        }
        protocol.MarkAudioUnavailable();
    }

    public static ProbeResult Probe(TimeSpan duration)
    {
        using var stop = new CancellationTokenSource(duration);
        bool started = false;
        int chunks = 0;
        Capture(null, stop.Token, () => started = true, _ => chunks++);
        return new ProbeResult(started, chunks);
    }

    private static void Capture(SharedDesktopProtocol? protocol, CancellationToken stop, Action? onStarted, Action<byte[]>? onChunk)
    {
        int comResult = CoInitializeEx(IntPtr.Zero, CoInitMultithreaded);
        bool uninitialize = comResult >= 0;
        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        IAudioClient? audioClient = null;
        IAudioCaptureClient? captureClient = null;
        try
        {
            Type enumeratorType = Type.GetTypeFromCLSID(DeviceEnumeratorClsid, true)
                ?? throw new InvalidOperationException("Windows audio endpoint enumerator is unavailable");
            enumerator = (IMMDeviceEnumerator)(Activator.CreateInstance(enumeratorType)
                ?? throw new InvalidOperationException("Could not create Windows audio endpoint enumerator"));
            Check(enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia, out device), "GetDefaultAudioEndpoint");
            Guid audioClientIid = AudioClientIid;
            Check(device.Activate(ref audioClientIid, ClsctxAll, IntPtr.Zero, out object clientObject), "IMMDevice.Activate");
            audioClient = (IAudioClient)clientObject;

            var format = new WaveFormatEx
            {
                FormatTag = 1,
                Channels = Channels,
                SamplesPerSec = SampleRate,
                BitsPerSample = BitsPerSample,
                BlockAlign = Channels * BitsPerSample / 8,
                AvgBytesPerSec = SampleRate * Channels * BitsPerSample / 8,
                ExtraSize = 0
            };
            uint flags = StreamFlagsLoopback | StreamFlagsAutoConvertPcm | StreamFlagsSrcDefaultQuality;
            Check(audioClient.Initialize(ShareMode.Shared, flags, 1_000_000, 0, ref format, IntPtr.Zero), "IAudioClient.Initialize");
            Guid captureClientIid = AudioCaptureClientIid;
            Check(audioClient.GetService(ref captureClientIid, out object captureObject), "IAudioClient.GetService");
            captureClient = (IAudioCaptureClient)captureObject;
            Check(audioClient.Start(), "IAudioClient.Start");
            protocol?.MarkAudioAvailable();
            onStarted?.Invoke();
            EventLogWriter.Info("audio.loopback_started format=48000/16/2");

            byte[] pending = new byte[BytesPerChunk * 16];
            int pendingLength = 0;
            while (!stop.IsCancellationRequested)
            {
                if (protocol != null && protocol.ReadInt32(SharedDesktopProtocol.OffsetAudioRequested) != 1) return;
                protocol?.MarkAudioAvailable();
                Check(captureClient.GetNextPacketSize(out uint packetFrames), "IAudioCaptureClient.GetNextPacketSize");
                while (packetFrames > 0)
                {
                    Check(captureClient.GetBuffer(out IntPtr data, out uint frames, out uint bufferFlags, out _, out _),
                        "IAudioCaptureClient.GetBuffer");
                    try
                    {
                        int byteCount = checked((int)frames * format.BlockAlign);
                        EnsureCapacity(ref pending, pendingLength + byteCount);
                        if ((bufferFlags & BufferFlagsSilent) != 0 || data == IntPtr.Zero)
                        {
                            Array.Clear(pending, pendingLength, byteCount);
                        }
                        else
                        {
                            Marshal.Copy(data, pending, pendingLength, byteCount);
                        }
                        pendingLength += byteCount;
                    }
                    finally
                    {
                        Check(captureClient.ReleaseBuffer(frames), "IAudioCaptureClient.ReleaseBuffer");
                    }

                    while (pendingLength >= BytesPerChunk)
                    {
                        byte[] chunk = new byte[BytesPerChunk];
                        Buffer.BlockCopy(pending, 0, chunk, 0, BytesPerChunk);
                        pendingLength -= BytesPerChunk;
                        if (pendingLength > 0) Buffer.BlockCopy(pending, BytesPerChunk, pending, 0, pendingLength);
                        protocol?.PublishAudio(chunk, BitsPerSample, SampleRate, Channels, FramesPerChunk);
                        onChunk?.Invoke(chunk);
                    }
                    Check(captureClient.GetNextPacketSize(out packetFrames), "IAudioCaptureClient.GetNextPacketSize");
                }
                stop.WaitHandle.WaitOne(5);
            }
        }
        finally
        {
            if (audioClient != null)
            {
                try { audioClient.Stop(); } catch { }
            }
            ReleaseCom(captureClient);
            ReleaseCom(audioClient);
            ReleaseCom(device);
            ReleaseCom(enumerator);
            if (uninitialize) CoUninitialize();
        }
    }

    private static void EnsureCapacity(ref byte[] buffer, int required)
    {
        if (required <= buffer.Length) return;
        Array.Resize(ref buffer, Math.Max(required, buffer.Length * 2));
    }

    private static void Check(int result, string operation)
    {
        if (result < 0) Marshal.ThrowExceptionForHR(result, new IntPtr(-1));
    }

    private static void ReleaseCom(object? value)
    {
        if (value != null && Marshal.IsComObject(value))
        {
            try { Marshal.FinalReleaseComObject(value); } catch { }
        }
    }

    private enum DataFlow { Render = 0, Capture = 1, All = 2 }
    private enum Role { Console = 0, Multimedia = 1, Communications = 2 }
    private enum ShareMode { Shared = 0, Exclusive = 1 }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    private struct WaveFormatEx
    {
        public ushort FormatTag;
        public ushort Channels;
        public uint SamplesPerSec;
        public uint AvgBytesPerSec;
        public ushort BlockAlign;
        public ushort BitsPerSample;
        public ushort ExtraSize;
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(DataFlow dataFlow, uint stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(DataFlow dataFlow, Role role, out IMMDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr callback);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr callback);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint clsctx, IntPtr activationParameters,
            [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        [PreserveSig] int OpenPropertyStore(uint access, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out uint state);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        [PreserveSig] int Initialize(ShareMode shareMode, uint streamFlags, long bufferDuration, long periodicity,
            ref WaveFormatEx format, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint paddingFrames);
        [PreserveSig] int IsFormatSupported(ShareMode shareMode, ref WaveFormatEx format, out IntPtr closestMatch);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [DllImport("ole32.dll")] private static extern int CoInitializeEx(IntPtr reserved, int coInit);
    [DllImport("ole32.dll")] private static extern void CoUninitialize();

    public readonly record struct ProbeResult(bool Started, int Chunks);
}
