package common;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class RollbackMonitor extends Handler {

    private final Logger sourceLogger;
    private final Logger pluginLogger;
    private final String platform;

    private RollbackMonitor(Logger sourceLogger, Logger pluginLogger, String platform) {
        this.sourceLogger = sourceLogger;
        this.pluginLogger = pluginLogger;
        this.platform = platform;
        setLevel(Level.ALL);
    }

    public static RollbackMonitor attach(Logger sourceLogger, Logger pluginLogger, String platform) {
        if (sourceLogger == null || pluginLogger == null) {
            return null;
        }
        RollbackMonitor monitor = new RollbackMonitor(sourceLogger, pluginLogger, platform);
        sourceLogger.addHandler(monitor);
        return monitor;
    }

    public void detach() {
        try {
            sourceLogger.removeHandler(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (!UpdateOptions.rollbackEnabled) return;
        if (record == null) return;
        if (!isLoggable(record)) return;

        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.toLowerCase(Locale.ROOT).contains("autoupdateplugins")) {
            return;
        }
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message != null && params != null && params.length > 0) {
            try {
                message = MessageFormat.format(message, params);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (message != null && !message.isEmpty()) {
            RollbackManager.handleLogLine(pluginLogger, platform, message);
        }
        if (record.getThrown() != null) {
            RollbackManager.handleThrowable(pluginLogger, platform, record.getThrown());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        detach();
    }
}
