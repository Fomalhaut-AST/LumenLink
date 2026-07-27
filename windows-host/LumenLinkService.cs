using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Principal;

namespace LumenLink.WindowsHost;

internal static class LumenLinkService
{
    private const string ServiceName = "LumenLinkSecureDesktop";
    private const uint ServiceWin32OwnProcess = 0x10;
    private const uint ServiceStartPending = 0x2;
    private const uint ServiceStopPending = 0x3;
    private const uint ServiceRunning = 0x4;
    private const uint ServiceStopped = 0x1;
    private const uint ServiceAcceptStop = 0x1;
    private const uint ServiceAcceptShutdown = 0x4;
    private const uint ServiceControlStop = 0x1;
    private const uint ServiceControlShutdown = 0x5;
    private const uint TokenAllAccess = 0x000F01FF;
    private const uint CreateUnicodeEnvironment = 0x00000400;
    private const uint CreateNoWindow = 0x08000000;
    private const int SecurityImpersonation = 2;
    private const int TokenPrimary = 1;
    private const int TokenSessionId = 12;
    private const int TokenUser = 1;

    private static readonly ManualResetEventSlim StopSignal = new(false);
    private static readonly ServiceMainDelegate ServiceMainCallback = ServiceMain;
    private static readonly HandlerExDelegate HandlerCallback = Handler;
    private static IntPtr statusHandle;
    private static Process? agent;

    public static int Run()
    {
        var table = new[]
        {
            new ServiceTableEntry { ServiceName = ServiceName, ServiceMain = ServiceMainCallback },
            new ServiceTableEntry { ServiceName = null, ServiceMain = null }
        };
        if (!StartServiceCtrlDispatcher(table))
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not connect to Windows Service Control Manager");
        }
        return 0;
    }

    private static void ServiceMain(int argumentCount, IntPtr arguments)
    {
        statusHandle = RegisterServiceCtrlHandlerEx(ServiceName, HandlerCallback, IntPtr.Zero);
        if (statusHandle == IntPtr.Zero) return;
        ReportStatus(ServiceStartPending, 0, 3000);
        ReportStatus(ServiceRunning, ServiceAcceptStop | ServiceAcceptShutdown, 0);
        EventLogWriter.Info("secure_desktop.service_started");

        uint agentSession = uint.MaxValue;
        long handledSas = 0;
        SharedDesktopProtocol? protocol = null;
        try
        {
            while (!StopSignal.Wait(TimeSpan.FromMilliseconds(500)))
            {
                uint session = WTSGetActiveConsoleSessionId();
                if (session == uint.MaxValue) continue;
                if (agent == null || agent.HasExited || agentSession != session)
                {
                    StopAgent();
                    protocol?.Dispose();
                    protocol = null;
                    agent = LaunchAgent(session);
                    agentSession = session;
                    handledSas = 0;
                }

                protocol ??= SharedDesktopProtocol.Open(session);
                if (protocol == null) continue;
                long request = protocol.ReadInt64(SharedDesktopProtocol.OffsetSasRequestSequence);
                if (request > handledSas)
                {
                    TrySendSecureAttention(session);
                    handledSas = request;
                    protocol.WriteInt64(SharedDesktopProtocol.OffsetSasHandledSequence, handledSas);
                }
            }
        }
        catch (Exception error)
        {
            EventLogWriter.Error("secure_desktop.service_loop_failed", error);
        }
        finally
        {
            protocol?.Dispose();
            StopAgent();
            ReportStatus(ServiceStopped, 0, 0);
            EventLogWriter.Info("secure_desktop.service_stopped");
        }
    }

    private static int Handler(uint control, uint eventType, IntPtr eventData, IntPtr context)
    {
        if (control == ServiceControlStop || control == ServiceControlShutdown)
        {
            ReportStatus(ServiceStopPending, 0, 5000);
            StopSignal.Set();
        }
        return 0;
    }

    private static Process LaunchAgent(uint sessionId)
    {
        string userSid = ActiveUserSid(sessionId);
        if (!OpenProcessToken(GetCurrentProcess(), TokenAllAccess, out IntPtr processToken))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "OpenProcessToken failed");
        try
        {
            if (!DuplicateTokenEx(processToken, TokenAllAccess, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out IntPtr primaryToken))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "DuplicateTokenEx failed");
            try
            {
                IntPtr sessionValue = Marshal.AllocHGlobal(sizeof(uint));
                try
                {
                    Marshal.WriteInt32(sessionValue, unchecked((int)sessionId));
                    if (!SetTokenInformation(primaryToken, TokenSessionId, sessionValue, sizeof(uint)))
                        throw new Win32Exception(Marshal.GetLastWin32Error(), "SetTokenInformation(TokenSessionId) failed");
                }
                finally
                {
                    Marshal.FreeHGlobal(sessionValue);
                }

                string executable = Environment.ProcessPath ?? throw new InvalidOperationException("Missing service executable path");
                string command = $"\"{executable}\" --agent --session {sessionId} --user-sid {userSid}";
                var startup = new StartupInfo
                {
                    Size = Marshal.SizeOf<StartupInfo>(),
                    Desktop = "winsta0\\Winlogon"
                };
                if (!CreateProcessAsUser(primaryToken, null, command, IntPtr.Zero, IntPtr.Zero, false,
                    CreateUnicodeEnvironment | CreateNoWindow, IntPtr.Zero, Path.GetDirectoryName(executable), ref startup, out ProcessInformation info))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcessAsUser for secure desktop agent failed");
                }
                CloseHandle(info.Thread);
                var launched = Process.GetProcessById(unchecked((int)info.ProcessId));
                CloseHandle(info.Process);
                EventLogWriter.Info($"secure_desktop.agent_launched session={sessionId}");
                return launched;
            }
            finally
            {
                CloseHandle(primaryToken);
            }
        }
        finally
        {
            CloseHandle(processToken);
        }
    }

    private static string ActiveUserSid(uint sessionId)
    {
        if (!WTSQueryUserToken(sessionId, out IntPtr token))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "WTSQueryUserToken failed");
        try
        {
            GetTokenInformation(token, TokenUser, IntPtr.Zero, 0, out int length);
            IntPtr buffer = Marshal.AllocHGlobal(length);
            try
            {
                if (!GetTokenInformation(token, TokenUser, buffer, length, out _))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "GetTokenInformation(TokenUser) failed");
                IntPtr sid = Marshal.ReadIntPtr(buffer);
                if (!ConvertSidToStringSid(sid, out IntPtr stringSid))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "ConvertSidToStringSid failed");
                try { return Marshal.PtrToStringUni(stringSid) ?? throw new InvalidOperationException("Empty user SID"); }
                finally { LocalFree(stringSid); }
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
        finally
        {
            CloseHandle(token);
        }
    }

    private static void TrySendSecureAttention(uint sessionId)
    {
        if (!WTSQueryUserToken(sessionId, out IntPtr token))
        {
            EventLogWriter.Error("secure_desktop.sas_user_token_failed");
            return;
        }
        try
        {
            if (!ImpersonateLoggedOnUser(token))
            {
                EventLogWriter.Error("secure_desktop.sas_impersonation_failed");
                return;
            }
            try
            {
                SendSAS(true);
                EventLogWriter.Info($"secure_desktop.sas_sent session={sessionId}");
            }
            catch (Exception error)
            {
                EventLogWriter.Error("secure_desktop.sas_failed", error);
            }
            finally
            {
                RevertToSelf();
            }
        }
        finally
        {
            CloseHandle(token);
        }
    }

    private static void StopAgent()
    {
        Process? current = agent;
        agent = null;
        if (current == null) return;
        try
        {
            if (!current.HasExited) current.Kill(true);
            current.WaitForExit(3000);
        }
        catch { }
        finally { current.Dispose(); }
    }

    private static void ReportStatus(uint state, uint acceptedControls, uint waitHint)
    {
        if (statusHandle == IntPtr.Zero) return;
        var status = new ServiceStatus
        {
            ServiceType = ServiceWin32OwnProcess,
            CurrentState = state,
            ControlsAccepted = acceptedControls,
            Win32ExitCode = 0,
            WaitHint = waitHint
        };
        SetServiceStatus(statusHandle, ref status);
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void ServiceMainDelegate(int argumentCount, IntPtr arguments);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate int HandlerExDelegate(uint control, uint eventType, IntPtr eventData, IntPtr context);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ServiceTableEntry
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string? ServiceName;
        public ServiceMainDelegate? ServiceMain;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ServiceStatus
    {
        public uint ServiceType;
        public uint CurrentState;
        public uint ControlsAccepted;
        public uint Win32ExitCode;
        public uint ServiceSpecificExitCode;
        public uint CheckPoint;
        public uint WaitHint;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int Size;
        public string? Reserved;
        public string? Desktop;
        public string? Title;
        public int X;
        public int Y;
        public int XSize;
        public int YSize;
        public int XCountChars;
        public int YCountChars;
        public int FillAttribute;
        public int Flags;
        public short ShowWindow;
        public short Reserved2;
        public IntPtr Reserved2Pointer;
        public IntPtr StandardInput;
        public IntPtr StandardOutput;
        public IntPtr StandardError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        public IntPtr Process;
        public IntPtr Thread;
        public uint ProcessId;
        public uint ThreadId;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool StartServiceCtrlDispatcher([In] ServiceTableEntry[] serviceTable);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr RegisterServiceCtrlHandlerEx(string serviceName, HandlerExDelegate handler, IntPtr context);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetServiceStatus(IntPtr serviceStatusHandle, ref ServiceStatus serviceStatus);
    [DllImport("kernel32.dll")] private static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", SetLastError = true)] private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool DuplicateTokenEx(IntPtr existing, uint access,
        IntPtr attributes, int impersonationLevel, int tokenType, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool SetTokenInformation(IntPtr token, int informationClass,
        IntPtr information, int informationLength);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool GetTokenInformation(IntPtr token, int informationClass,
        IntPtr information, int informationLength, out int returnLength);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool ConvertSidToStringSid(IntPtr sid, out IntPtr stringSid);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CreateProcessAsUser(IntPtr token,
        string? applicationName, string commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags,
        IntPtr environment, string? currentDirectory, ref StartupInfo startupInfo, out ProcessInformation processInformation);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool ImpersonateLoggedOnUser(IntPtr token);
    [DllImport("advapi32.dll")] private static extern bool RevertToSelf();
    [DllImport("sas.dll")] private static extern void SendSAS([MarshalAs(UnmanagedType.Bool)] bool asUser);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
}
