package bungeecord;

import common.ConfigManager;
import common.CronScheduler;
import common.PluginUpdater;
import common.UpdateOptions;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class BungeeUpdate extends Plugin {

    private PluginUpdater pluginUpdater;
    private File myFile;
    private ConfigManager cfgMgr;

    @Override
    public void onEnable() {
        new Metrics(this, 18456);
        pluginUpdater = new PluginUpdater(this.getLogger());

        cfgMgr = new ConfigManager(getDataFolder(), "config.yml");
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
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
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new UpdateCommand());
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new AupCommand(pluginUpdater, myFile, cfgMgr, this::reloadPluginConfig));
    }

    private void reloadPluginConfig() {
        this.cfgMgr = new ConfigManager(getDataFolder(), "config.yml");
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
        getLogger().info("AutoUpdatePlugins configuration reloaded.");
    }

    public void periodUpdatePlugins() {
        String cronExpression = cfgMgr.getString("updates.schedule.cron");

        boolean scheduled = false;
        if (cronExpression != null && !cronExpression.isEmpty()) {
            String tz = cfgMgr.getString("updates.schedule.timezone");
            scheduled = CronScheduler.scheduleRecurring(
                    cronExpression,
                    tz,
                    (delay, task) -> getProxy().getScheduler().schedule(this, task, delay, TimeUnit.SECONDS),
                    getLogger(),
                    () -> getProxy().getScheduler().runAsync(this, () -> pluginUpdater.readList(myFile, "waterfall", cfgMgr.getString("updates.key")))
            );
        }
        if (!scheduled) {
            scheduleIntervalUpdates();
        }
    }

    private void scheduleIntervalUpdates() {
        int interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");
        long periodSeconds = 60L * interval;
        getProxy().getScheduler().schedule(this,
                () -> getProxy().getScheduler().runAsync(this, () -> pluginUpdater.readList(myFile, "waterfall", cfgMgr.getString("updates.key"))),
                bootTime, periodSeconds, TimeUnit.SECONDS);
        getLogger().info("Scheduled updates with interval: " + interval + " minutes (First run in " + bootTime + " seconds)");
    }

    private void applyHttpConfigBungee(net.md_5.bungee.config.Configuration config) {
        try {
            boolean sslVerify = config.getBoolean("http.sslVerify", true);
            common.UpdateOptions.sslVerify = sslVerify;
            if (!sslVerify) {
                javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                            }

                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                        }
                };
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new java.security.SecureRandom());
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleUpdateFolder() {
        java.nio.file.Path updateDir = getDataFolder().getParentFile().toPath().resolve("update");
        if (java.nio.file.Files.isDirectory(updateDir)) {
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(updateDir)) {
                s.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .forEach(jar -> {
                            try {
                                java.nio.file.Path target = getDataFolder().getParentFile().toPath().resolve(jar.getFileName());
                                java.nio.file.Files.move(jar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                getLogger().info("[AutoUpdatePlugins] Updated " + jar.getFileName() + " from update folder.");
                            } catch (java.io.IOException e) {
                                e.printStackTrace();
                            }
                        });
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }
    }

    // === New: HTTP/Proxy/TLS + headers/UA from cfgMgr (Bungee) ===
    private void applyHttpConfigFromCfg() {
        try {
            String userAgent = cfgMgr.getString("http.userAgent");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            java.util.List<java.util.Map<String, Object>> list =
                    (java.util.List<java.util.Map<String, Object>>) cfgMgr.getList("http.headers");
            if (list != null) {
                for (java.util.Map<String, Object> m : list) {
                    Object n = m.get("name"), v = m.get("value");
                    if (n != null && v != null) headers.put(n.toString(), v.toString());
                }
            }
            common.PluginDownloader.setHttpHeaders(headers, userAgent);
        } catch (Throwable ignored) {
        }
        try {
            String type = cfgMgr.getString("proxy.type");
            String host = cfgMgr.getString("proxy.host");
            int port = cfgMgr.getInt("proxy.port");
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
        } catch (Throwable ignored) {
        }
        try {
            boolean sslVerify = cfgMgr.getBoolean("http.sslVerify");
            common.UpdateOptions.sslVerify = sslVerify;
            if (!sslVerify) {
                javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                            }

                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                        }
                };
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new java.security.SecureRandom());
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            }
        } catch (Throwable ignored) {
        }
    }


    private void applyBehaviorConfig() {
        try {
            common.UpdateOptions.zipFileCheck = cfgMgr.getBoolean("behavior.zipFileCheck");
            common.UpdateOptions.ignoreDuplicates = cfgMgr.getBoolean("behavior.ignoreDuplicates");
            common.UpdateOptions.autoCompileEnable = cfgMgr.getBoolean("behavior.autoCompile.enable");
            common.UpdateOptions.autoCompileWhenNoJarAsset = cfgMgr.getBoolean("behavior.autoCompile.whenNoJarAsset");
            common.UpdateOptions.autoCompileBranchNewerMonths = cfgMgr.getInt("behavior.autoCompile.branchNewerMonths");
            common.UpdateOptions.allowPreReleaseDefault = cfgMgr.getBoolean("behavior.allowPreRelease");
            common.UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
            common.UpdateOptions.debug = cfgMgr.getBoolean("behavior.debug");
            common.UpdateOptions.tempPath = cfgMgr.getString("paths.tempPath");
            common.UpdateOptions.updatePath = cfgMgr.getString("paths.updatePath");
            common.UpdateOptions.filePath = cfgMgr.getString("paths.filePath");
            common.UpdateOptions.maxParallel = Math.max(1, cfgMgr.getInt("performance.maxParallel"));
            common.UpdateOptions.connectTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.connectTimeoutMs"));
            common.UpdateOptions.readTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.readTimeoutMs"));
            common.UpdateOptions.perDownloadTimeoutSec = Math.max(0, cfgMgr.getInt("performance.perDownloadTimeoutSec"));
            common.UpdateOptions.maxRetries = Math.max(1, cfgMgr.getInt("performance.maxRetries"));
            common.UpdateOptions.backoffBaseMs = Math.max(0, cfgMgr.getInt("performance.backoffBaseMs"));
            common.UpdateOptions.backoffMaxMs = Math.max(common.UpdateOptions.backoffBaseMs, cfgMgr.getInt("performance.backoffMaxMs"));
            common.UpdateOptions.maxPerHost = Math.max(1, cfgMgr.getInt("performance.maxPerHost"));
            java.util.List<java.util.Map<String, Object>> uaList =
                    (java.util.List<java.util.Map<String, Object>>) cfgMgr.getList("http.userAgents");
            common.UpdateOptions.userAgents.clear();
            if (uaList != null) {
                for (java.util.Map<String, Object> m : uaList) {
                    Object v = m.get("ua");
                    if (v != null) common.UpdateOptions.userAgents.add(v.toString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void generateOrUpdateConfig() {
        cfgMgr.addDefault("updates.interval", 120, "Time between plugin updates in minutes");
        cfgMgr.addDefault("updates.bootTime", 50, "Delay in seconds after server startup before updating");
        cfgMgr.addDefault("updates.schedule.cron", "", "Experimental: A cron expression to schedule updates. Overrides interval and bootTime if set.");
        cfgMgr.addDefault("updates.schedule.timezone", "UTC", "The timezone for the cron schedule.");
        cfgMgr.addDefault("updates.key", "", "GitHub token for Actions/authenticated requests (optional)");

        cfgMgr.addDefault("http.userAgent", "AutoUpdatePlugins", "HTTP User-Agent override (leave blank to auto-rotate)");
        cfgMgr.addDefault("http.headers", new java.util.ArrayList<>(), "Extra headers: list of {name, value}");
        java.util.ArrayList<java.util.Map<String, String>> uas = new java.util.ArrayList<>();
        java.util.HashMap<String, String> ua1 = new java.util.HashMap<>();
        ua1.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        java.util.HashMap<String, String> ua2 = new java.util.HashMap<>();
        ua2.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        java.util.HashMap<String, String> ua3 = new java.util.HashMap<>();
        ua3.put("ua", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/126.0");
        uas.add(ua1);
        uas.add(ua2);
        uas.add(ua3);
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

    @SuppressWarnings("unchecked")
    public class UpdateCommand extends Command {

        public UpdateCommand() {
            super("update", "autoupdateplugins.update");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (pluginUpdater.isUpdating()) {
                sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
                return;
            }
            pluginUpdater.readList(myFile, "waterfall", cfgMgr.getString("updates.key"));
            sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
        }
    }
}
