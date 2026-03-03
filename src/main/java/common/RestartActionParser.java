package common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class RestartActionParser {

    private RestartActionParser() {
    }

    public static List<UpdateOptions.RestartAction> parse(Object rawList, Logger logger) {
        List<UpdateOptions.RestartAction> out = new ArrayList<>();
        if (!(rawList instanceof Iterable<?>)) {
            return out;
        }

        for (Object item : (Iterable<?>) rawList) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;

            int timeToRestart = parseTimeToRestart(map);
            if (timeToRestart < 0) {
                if (UpdateOptions.debug && logger != null) {
                    logger.info("[DEBUG] Ignoring restartCommands entry without valid timeToRestart: " + map);
                }
                continue;
            }

            String command = normalizeString(firstNonNull(
                    map.get("command"),
                    map.get("cmd"),
                    map.get("run")
            ));
            String message = normalizeString(firstNonNull(
                    map.get("message"),
                    map.get("msg"),
                    map.get("broadcast")
            ));

            if ((command == null || command.isEmpty()) && (message == null || message.isEmpty())) {
                if (UpdateOptions.debug && logger != null) {
                    logger.info("[DEBUG] Ignoring restartCommands entry with no command/message: " + map);
                }
                continue;
            }

            out.add(new UpdateOptions.RestartAction(timeToRestart, command, message));
        }

        out.sort(Comparator.comparingInt((UpdateOptions.RestartAction a) -> a.timeToRestartSec).reversed());
        return out;
    }

    private static int parseTimeToRestart(Map<?, ?> map) {
        Object value = firstNonNull(
                map.get("timeToRestart"),
                map.get("secondsBeforeRestart"),
                map.get("time"),
                map.get("delay")
        );
        if (value == null) return -1;

        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }

        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String normalizeString(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
