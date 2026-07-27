namespace LumenLink.WindowsHost;

internal static class Program
{
    public static int Main(string[] args)
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.Error.WriteLine("LumenLink.WindowsHost only supports Windows.");
            return 1;
        }

        try
        {
            if (args.Contains("--audio-test", StringComparer.OrdinalIgnoreCase))
            {
                var result = WasapiLoopbackCapture.Probe(TimeSpan.FromSeconds(3));
                Console.WriteLine(result.Started
                    ? $"WASAPI loopback initialized; captured {result.Chunks} PCM chunks."
                    : "WASAPI loopback did not initialize.");
                return result.Started ? 0 : 4;
            }

            if (args.Contains("--service", StringComparer.OrdinalIgnoreCase))
            {
                return LumenLinkService.Run();
            }

            if (args.Contains("--agent", StringComparer.OrdinalIgnoreCase))
            {
                uint sessionId = uint.Parse(RequiredOption(args, "--session"));
                string userSid = RequiredOption(args, "--user-sid");
                return SecureDesktopAgent.Run(sessionId, userSid);
            }

            Console.Error.WriteLine("Use --service, --agent, or --audio-test.");
            return 2;
        }
        catch (Exception error)
        {
            EventLogWriter.Error("windows_host.failed", error);
            return 3;
        }
    }

    private static string RequiredOption(string[] args, string option)
    {
        int index = Array.FindIndex(args, value => value.Equals(option, StringComparison.OrdinalIgnoreCase));
        if (index < 0 || index + 1 >= args.Length || string.IsNullOrWhiteSpace(args[index + 1]))
        {
            throw new ArgumentException($"Missing required option {option}");
        }
        return args[index + 1];
    }
}
