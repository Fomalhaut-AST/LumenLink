namespace LumenLink.WindowsHost;

internal static class EventLogWriter
{
    private const long MaxBytes = 2L * 1024 * 1024;
    private static readonly object Sync = new();

    public static void Info(string message) => Write("INFO", message);

    public static void Error(string message, Exception? error = null)
    {
        string detail = error == null ? message : $"{message}: {error.GetType().Name}: {error.Message}";
        Write("ERROR", detail);
    }

    private static void Write(string level, string message)
    {
        try
        {
            lock (Sync)
            {
                string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "LumenLink", "logs");
                Directory.CreateDirectory(directory);
                string path = Path.Combine(directory, "secure-desktop.log");
                if (File.Exists(path) && new FileInfo(path).Length >= MaxBytes)
                {
                    string previous = path + ".1";
                    File.Move(path, previous, true);
                }
                File.AppendAllText(path, $"{DateTimeOffset.UtcNow:O} {level} {message}{Environment.NewLine}");
            }
        }
        catch
        {
            try { Console.Error.WriteLine($"{level} {message}"); } catch { }
        }
    }
}
