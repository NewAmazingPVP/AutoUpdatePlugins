package common;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public class SchedulerAdapter {

    private final Plugin plugin;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void runRepeatingAsync(long initialDelaySeconds, long periodSeconds, Runnable task) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Method getAsyncScheduler = bukkitClass.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncScheduler.invoke(null);
            Class<?> asyncSchedulerClass = asyncScheduler.getClass();
            Method runAtFixedRate = asyncSchedulerClass.getMethod(
                    "runAtFixedRate",
                    Class.forName("org.bukkit.plugin.Plugin"),
                    java.util.function.Consumer.class,
                    long.class,
                    long.class,
                    TimeUnit.class
            );
            java.util.function.Consumer<Object> consumer = (ignored) -> task.run();
            runAtFixedRate.invoke(asyncScheduler, plugin, consumer, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
            return;
        } catch (Throwable ignored) {}

        long initialTicks = initialDelaySeconds * 20L;
        long periodTicks = periodSeconds * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialTicks, periodTicks);
    }

    public void runDelayedAsync(long delaySeconds, Runnable task) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Method getAsyncScheduler = bukkitClass.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncScheduler.invoke(null);
            Class<?> asyncSchedulerClass = asyncScheduler.getClass();
            Method runDelayed = asyncSchedulerClass.getMethod(
                    "runDelayed",
                    Class.forName("org.bukkit.plugin.Plugin"),
                    java.util.function.Consumer.class,
                    long.class,
                    TimeUnit.class
            );
            java.util.function.Consumer<Object> consumer = (ignored) -> task.run();
            runDelayed.invoke(asyncScheduler, plugin, consumer, delaySeconds, TimeUnit.SECONDS);
            return;
        } catch (Throwable ignored) {}

        long delayTicks = delaySeconds * 20L;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }
}
