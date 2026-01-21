package common;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.logging.Logger;

public class CronScheduler {

    public interface OneShotScheduler {
        void schedule(long delaySeconds, Runnable task);
    }

    public static boolean scheduleRecurring(String cronExpression,
                                            String timezoneId,
                                            OneShotScheduler scheduler,
                                            Logger logger,
                                            Runnable job) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) return false;
        try {
            CronDefinition def = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
            CronParser parser = new CronParser(def);
            Cron cron = parser.parse(cronExpression);
            final ExecutionTime exec = ExecutionTime.forCron(cron);
            final ZoneId zone = ZoneId.of(timezoneId == null || timezoneId.isEmpty() ? "UTC" : timezoneId);

            Runnable[] wrapper = new Runnable[1];
            wrapper[0] = () -> {
                try {
                    job.run();
                } catch (Throwable t) {
                    logger.warning("Cron job threw an exception: " + t.getMessage());
                }
                long nextDelay = computeNextDelaySeconds(exec, zone, logger, cronExpression);
                if (nextDelay >= 0) {
                    scheduler.schedule(nextDelay, wrapper[0]);
                }
            };

            long initialDelay = computeNextDelaySeconds(exec, zone, logger, cronExpression);
            if (initialDelay < 0) return false;
            logger.info("Scheduled cron updates: " + cronExpression + " (next in " + initialDelay + "s, timezone=" + zone + ")");
            scheduler.schedule(initialDelay, wrapper[0]);
            return true;
        } catch (Exception ex) {
            logger.warning("Invalid cron expression '" + cronExpression + "': " + ex.getMessage());
            return false;
        }
    }

    private static long computeNextDelaySeconds(ExecutionTime exec, ZoneId zone, Logger logger, String expr) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        Optional<ZonedDateTime> next = exec.nextExecution(now);
        if (next.isPresent()) {
            long millis = Duration.between(now, next.get()).toMillis();
            if (millis <= 0) {
                return 0;
            }
            long seconds = (millis + 999) / 1000;
            return Math.max(1, seconds);
        } else {
            logger.warning("Could not determine next cron execution for: " + expr);
            return -1;
        }
    }
}
