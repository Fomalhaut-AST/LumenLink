package io.lumenlink.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Rotating operational log that never accepts protocol payloads or exception messages. */
public final class SafeLog {
    private static final Logger LOG = createLogger();

    private SafeLog() { }

    public static void info(String event) {
        LOG.log(Level.INFO, sanitizeEvent(event));
    }

    public static void warn(String event, Throwable error) {
        String type = error == null ? "unknown" : error.getClass().getSimpleName();
        LOG.log(Level.WARNING, sanitizeEvent(event) + " errorType=" + type);
    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger("io.lumenlink.client.safe");
        logger.setUseParentHandlers(false);
        try {
            Path directory = Path.of(System.getenv().getOrDefault("LUMENLINK_CLIENT_LOG_DIR",
                    Path.of(System.getProperty("user.home"), ".lumenlink", "logs").toString()));
            Files.createDirectories(directory);
            FileHandler handler = new FileHandler(directory.resolve("client-%g.log").toString(), 2 * 1024 * 1024, 5, true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new SafeFormatter());
            logger.addHandler(handler);
        } catch (Exception ignored) {
        }
        return logger;
    }

    private static String sanitizeEvent(String event) {
        if (event == null) return "unknown";
        String sanitized = event.replaceAll("[^A-Za-z0-9_.=-]", "_");
        return sanitized.substring(0, Math.min(160, sanitized.length()));
    }

    private static final class SafeFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return Instant.ofEpochMilli(record.getMillis()) + " " + record.getLevel() + " " + record.getMessage()
                    + System.lineSeparator();
        }
    }
}
