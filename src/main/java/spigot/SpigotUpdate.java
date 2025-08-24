package spigot;

import common.PluginUpdater;
import org.bukkit.Bukkit;
import common.SchedulerAdapter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import common.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;
import spigot.AupCommand;
import common.UpdateOptions;
import common.CronScheduler;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SpigotUpdate extends JavaPlugin {

    private PluginUpdater pluginUpdater;
    private File myFile;
    private FileConfiguration config;
    private ConfigManager cfgMgr;

    @Override
    public void onEnable() {
        new Metrics(this, 18454);
        pluginUpdater = new PluginUpdater(this.getLogger());
        config = getConfig();
        saveDefaultConfig();
        cfgMgr = new ConfigManager(getDataFolder(), "config.yml");
        generateOrUpdateConfig();
        File dataFolder = getDataFolder();
        myFile = new File(dataFolder, "list.yml");
        if (!myFile.exists()) {
            try {
                myFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        periodUpdatePlugins();
        getCommand("update").setExecutor(new UpdateCommand());
        if (getCommand("aup") != null) {
            AupCommand aup = new AupCommand(pluginUpdater, myFile, config, () -> cfgMgr.getString("updates.key"), cfgMgr, this::reloadPluginConfig);
            getCommand("aup").setExecutor(aup);
            getCommand("aup").setTabCompleter(aup);
        }

        applyHttpConfig();
        applyBehaviorConfig();
    }

    private void reloadPluginConfig() {
        try { this.reloadConfig(); } catch (Throwable ignored) {}
        this.config = getConfig();
        this.cfgMgr = new ConfigManager(getDataFolder(), "config.yml");
        generateOrUpdateConfig();
        applyHttpConfig();
        applyBehaviorConfig();
        getLogger().info("AutoUpdatePlugins configuration reloaded.");
    }

    public void periodUpdatePlugins() {
        String cronExpression = cfgMgr.getString("updates.schedule.cron");
        SchedulerAdapter sched = new SchedulerAdapter(this);
        Runnable job = () -> pluginUpdater.readList(myFile, serverPlatform(), cfgMgr.getString("updates.key"));

        boolean scheduled = false;
        if (cronExpression != null && !cronExpression.isEmpty()) {
            String tz = cfgMgr.getString("updates.schedule.timezone");
            scheduled = CronScheduler.scheduleRecurring(cronExpression, tz, sched::runDelayedAsync, getLogger(), job);
        }
        if (!scheduled) {
            scheduleIntervalUpdates();
        }
    }

    private void scheduleIntervalUpdates() {
        int interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");
        SchedulerAdapter sched = new SchedulerAdapter(this);
        sched.runRepeatingAsync(bootTime, 60L * interval, () -> pluginUpdater.readList(myFile, serverPlatform(), cfgMgr.getString("updates.key")));
        getLogger().info("Scheduled updates with interval: " + interval + " minutes (First run in " + bootTime + " seconds)");
    }

    private void applyHttpConfig() {
        try {
            String userAgent = config.getString("http.userAgent");
            Map<String, String> headers = new HashMap<>();
            if (config.isList("http.headers")) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) config.getList("http.headers");
                if (list != null) {
                    for (Map<String, Object> m : list) {
                        Object n = m.get("name");
                        Object v = m.get("value");
                        if (n != null && v != null) headers.put(n.toString(), v.toString());
                    }
                }
            }
            common.PluginDownloader.setHttpHeaders(headers, userAgent);
        } catch (Throwable ignored) {}

        try {
            String type = config.getString("proxy.type");
            String host = config.getString("proxy.host");
            int port = config.getInt("proxy.port");
            if (type != null && host != null && port > 0) {
                if ("HTTP".equalsIgnoreCase(type)) {
                    System.setProperty("http.proxyHost", host);
                    System.setProperty("http.proxyPort", Integer.toString(port));
                    System.setProperty("https.proxyHost", host);
                    System.setProperty("https.proxyPort", Integer.toString(port));
                } else if ("SOCKS".equalsIgnoreCase(type)) {
                    System.setProperty("socksProxyHost", host);
                    System.setProperty("socksProxyPort", Integer.toString(port));
                } else {
                    System.clearProperty("http.proxyHost");
                    System.clearProperty("http.proxyPort");
                    System.clearProperty("https.proxyHost");
                    System.clearProperty("https.proxyPort");
                    System.clearProperty("socksProxyHost");
                    System.clearProperty("socksProxyPort");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void applyBehaviorConfig() {
        try {
            UpdateOptions.zipFileCheck = config.getBoolean("behavior.zipFileCheck");
            UpdateOptions.ignoreDuplicates = config.getBoolean("behavior.ignoreDuplicates");
            UpdateOptions.autoCompileEnable = config.getBoolean("behavior.autoCompile.enable");
            UpdateOptions.autoCompileWhenNoJarAsset = config.getBoolean("behavior.autoCompile.whenNoJarAsset");
            UpdateOptions.autoCompileBranchNewerMonths = config.getInt("behavior.autoCompile.branchNewerMonths");
            UpdateOptions.allowPreReleaseDefault = config.getBoolean("behavior.allowPreRelease");
            UpdateOptions.useUpdateFolder = config.getBoolean("behavior.useUpdateFolder");
            UpdateOptions.debug = config.getBoolean("behavior.debug");
            UpdateOptions.tempPath = config.getString("paths.tempPath");
            UpdateOptions.updatePath = config.getString("paths.updatePath");
            UpdateOptions.filePath = config.getString("paths.filePath");
            UpdateOptions.maxParallel = Math.max(1, config.getInt("performance.maxParallel"));
            UpdateOptions.connectTimeoutMs = Math.max(1000, config.getInt("performance.connectTimeoutMs"));
            UpdateOptions.readTimeoutMs = Math.max(1000, config.getInt("performance.readTimeoutMs"));
            UpdateOptions.perDownloadTimeoutSec = Math.max(0, config.getInt("performance.perDownloadTimeoutSec"));
            UpdateOptions.maxRetries = Math.max(1, config.getInt("performance.maxRetries"));
            UpdateOptions.backoffBaseMs = Math.max(0, config.getInt("performance.backoffBaseMs"));
            UpdateOptions.backoffMaxMs = Math.max(UpdateOptions.backoffBaseMs, config.getInt("performance.backoffMaxMs"));
            UpdateOptions.maxPerHost = Math.max(1, config.getInt("performance.maxPerHost"));

            java.util.List<Map<String, Object>> uaList = (java.util.List<Map<String, Object>>) config.getList("http.userAgents");
            UpdateOptions.userAgents.clear();
            if (uaList != null) {
                for (Map<String, Object> m : uaList) {
                    Object v = m.get("ua");
                    if (v != null) UpdateOptions.userAgents.add(v.toString());
                }
            }

            
        } catch (Throwable ignored) {}
    }

    public class UpdateCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (pluginUpdater.isUpdating()) {
                sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
                return true;
            }
            pluginUpdater.readList(myFile, serverPlatform(), cfgMgr.getString("updates.key"));
            sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
            return true;
        }
    }

    private String serverPlatform() {
        try {
            // Presence on Folia servers
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return "folia";
        } catch (Throwable ignored) {}
        try {
            String v = Bukkit.getVersion();
            if (v != null && v.toLowerCase().contains("folia")) return "folia";
        } catch (Throwable ignored) {}
        return "paper";
    }

    private void generateOrUpdateConfig() {
        cfgMgr.addDefault("updates.interval", 120, "Time between plugin updates in minutes");
        cfgMgr.addDefault("updates.bootTime", 50, "Delay in seconds after server startup before updating");
        cfgMgr.addDefault("updates.schedule.cron", "", "Experimental: A cron expression to schedule updates. Overrides interval and bootTime if set.");
        cfgMgr.addDefault("updates.schedule.timezone", "UTC", "The timezone for the cron schedule.");
        cfgMgr.addDefault("updates.key", "", "GitHub token for Actions/authenticated requests (optional)");

        cfgMgr.addDefault("http.userAgent", "AutoUpdatePlugins", "HTTP User-Agent override (leave blank to auto-rotate)");
        cfgMgr.addDefault("http.headers", new java.util.ArrayList<>(), "Extra headers: list of {name, value}");
        java.util.ArrayList<Map<String, String>> uas = new java.util.ArrayList<>();
        java.util.HashMap<String, String> ua1 = new java.util.HashMap<>(); ua1.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        java.util.HashMap<String, String> ua2 = new java.util.HashMap<>(); ua2.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        java.util.HashMap<String, String> ua3 = new java.util.HashMap<>(); ua3.put("ua", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/126.0");
        uas.add(ua1); uas.add(ua2); uas.add(ua3);
        cfgMgr.addDefault("http.userAgents", uas, "Optional pool of User-Agents; rotates on retry");

        cfgMgr.addDefault("proxy.type", "DIRECT", "Proxy type: DIRECT | HTTP | SOCKS");
        cfgMgr.addDefault("proxy.host", "proxy.example.com", "Proxy host");
        cfgMgr.addDefault("proxy.port", 8080, "Proxy port");

        cfgMgr.addDefault("behavior.zipFileCheck", true, "Open .jar/.zip to verify integrity");
        cfgMgr.addDefault("behavior.ignoreDuplicates", true, "Skip replace when MD5 is identical");
        cfgMgr.addDefault("behavior.allowPreRelease", false, "Allow GitHub pre-releases by default");
        cfgMgr.addDefault("behavior.debug", false, "Enable verbose debug logging (toggle via /aup debug)");
        cfgMgr.addDefault("behavior.autoCompile.enable", true, "Enable source build fallback for GitHub");
        cfgMgr.addDefault("behavior.autoCompile.whenNoJarAsset", true, "Build when release has no jar assets");
        cfgMgr.addDefault("behavior.autoCompile.branchNewerMonths", 4, "Build when default branch is newer by N months");
        cfgMgr.addDefault("behavior.useUpdateFolder", true, "Use the update folder for updates. This requires a server restart to apply the update. For Velocity, it may require two restarts.");

        cfgMgr.addDefault("paths.tempPath", "", "Custom temp/cache path (optional)");
        cfgMgr.addDefault("paths.updatePath", "", "Custom update folder path (optional)");
        cfgMgr.addDefault("paths.filePath", "", "Custom final plugin path (optional)");

        cfgMgr.addDefault("performance.maxParallel", 4, "Parallel downloads (1 to CPU count)");
        cfgMgr.addDefault("performance.connectTimeoutMs", 10000, "HTTP connect timeout in ms");
        cfgMgr.addDefault("performance.readTimeoutMs", 30000, "HTTP read timeout in ms");
        cfgMgr.addDefault("performance.perDownloadTimeoutSec", 0, "Optional per-download cap (0=off)");
        cfgMgr.addDefault("performance.maxRetries", 4, "Retries per download on 403/429/5xx");
        cfgMgr.addDefault("performance.backoffBaseMs", 500, "Backoff base in ms for retries");
        cfgMgr.addDefault("performance.backoffMaxMs", 5000, "Backoff max in ms for retries");
        cfgMgr.addDefault("performance.maxPerHost", 3, "Max concurrent downloads per host");

        cfgMgr.saveConfig();
    }
}
