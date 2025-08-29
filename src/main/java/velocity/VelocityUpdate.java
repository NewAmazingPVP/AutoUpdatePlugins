package velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import common.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "12.1.2", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/", authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private PluginUpdater pluginUpdater;
    private final ProxyServer proxy;
    private File myFile;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private ConfigManager cfgMgr;
    private final Logger logger = Logger.getLogger("AutoUpdatePlugins");

    @Inject
    public VelocityUpdate(ProxyServer proxy, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    private void reloadPluginConfig() {
        this.cfgMgr = new ConfigManager(dataDirectory.toFile(), "config.yml");
        generateOrUpdateConfig();
        handleUpdateFolder();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        logger.info("AutoUpdatePlugins configuration reloaded.");
    }

    private void handleUpdateFolder() {
        Path updateDir = dataDirectory.getParent().resolve("update");
        if (Files.isDirectory(updateDir)) {
            try (Stream<Path> stream = Files.list(updateDir)) {
                stream.filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                        .forEach(jar -> {
                            try {
                                Path target = dataDirectory.getParent().resolve(jar.getFileName());
                                Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
                                logger.info("[AutoUpdatePlugins] Updated " + jar.getFileName() + " from update folder.");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        handleUpdateFolder();
        metricsFactory.make(this, 18455);
        pluginUpdater = new PluginUpdater(logger);
        cfgMgr = new ConfigManager(dataDirectory.toFile(), "config.yml");
        generateOrUpdateConfig();
        applyHttpConfigFromCfg();
        applyBehaviorConfig();
        UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
        myFile = dataDirectory.resolve("list.yml").toFile();
        ensureListFileWithExample(myFile);
        periodUpdatePlugins();
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta updateMeta = commandManager.metaBuilder("update").plugin(this).build();
        commandManager.register(updateMeta, new UpdateCommand());

        CommandMeta aupMeta = commandManager.metaBuilder("aup").aliases("autoupdateplugins").plugin(this).build();
        commandManager.register(aupMeta, new AupCommand(pluginUpdater, myFile, cfgMgr, this::reloadPluginConfig));
    }

    private void ensureListFileWithExample(File file) {
        try {
            boolean created = false;
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                created = file.createNewFile();
            }
            if (created || file.length() == 0) {
                String example = "# Map plugin name to its update source URL\n"
                        + "# Example entry:\n"
                        + "AutoUpdatePlugins: \"https://github.com/NewAmazingPVP/AutoUpdatePlugins\"\n";

                Path filePath = file.toPath();
                Files.write(filePath, example.getBytes(StandardCharsets.UTF_8));

                getLogger().info("Created example list.yml with a sample entry.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void periodUpdatePlugins() {
        String cronExpression = cfgMgr.getString("updates.schedule.cron");

        boolean scheduled = false;
        if (cronExpression != null && !cronExpression.isEmpty()) {
            String tz = cfgMgr.getString("updates.schedule.timezone");
            scheduled = CronScheduler.scheduleRecurring(
                    cronExpression,
                    tz,
                    (delay, task) -> proxy.getScheduler().buildTask(this, task).delay(Duration.ofSeconds(delay)).schedule(),
                    getLogger(),
                    () -> pluginUpdater.readList(myFile, "velocity", cfgMgr.getString("updates.key"))
            );
        }
        if (!scheduled) {
            scheduleIntervalUpdates();
        }
    }

    private void scheduleIntervalUpdates() {
        long interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");

        proxy.getScheduler().buildTask(this, () -> pluginUpdater.readList(myFile, "velocity", cfgMgr.getString("updates.key"))).delay(Duration.ofSeconds(bootTime)).repeat(Duration.ofMinutes(interval)).schedule();
        getLogger().info("Scheduled updates with interval: " + interval + " minutes (First run in " + bootTime + " seconds)");
    }

    private void applyHttpConfigFromCfg() {
        try {
            String userAgent = cfgMgr.getString("http.userAgent");
            Map<String, String> headers = new HashMap<>();
            List<Map<String, Object>> list =
                    (List<Map<String, Object>>) cfgMgr.getList("http.headers");
            if (list != null) {
                for (Map<String, Object> m : list) {
                    Object n = m.get("name"), v = m.get("value");
                    if (n != null && v != null) headers.put(n.toString(), v.toString());
                }
            }
            PluginDownloader.setHttpHeaders(headers, userAgent);
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
            UpdateOptions.sslVerify = sslVerify;
            if (!sslVerify) {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(X509Certificate[] c, String a) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyBehaviorConfig() {
        try {
            UpdateOptions.zipFileCheck = cfgMgr.getBoolean("behavior.zipFileCheck");
            UpdateOptions.ignoreDuplicates = cfgMgr.getBoolean("behavior.ignoreDuplicates");
            UpdateOptions.autoCompileEnable = cfgMgr.getBoolean("behavior.autoCompile.enable");
            UpdateOptions.autoCompileWhenNoJarAsset = cfgMgr.getBoolean("behavior.autoCompile.whenNoJarAsset");
            UpdateOptions.autoCompileBranchNewerMonths = cfgMgr.getInt("behavior.autoCompile.branchNewerMonths");
            UpdateOptions.allowPreReleaseDefault = cfgMgr.getBoolean("behavior.allowPreRelease");
            UpdateOptions.useUpdateFolder = cfgMgr.getBoolean("behavior.useUpdateFolder");
            UpdateOptions.debug = cfgMgr.getBoolean("behavior.debug");
            UpdateOptions.tempPath = cfgMgr.getString("paths.tempPath");
            UpdateOptions.updatePath = cfgMgr.getString("paths.updatePath");
            UpdateOptions.filePath = cfgMgr.getString("paths.filePath");
            UpdateOptions.maxParallel = Math.max(1, cfgMgr.getInt("performance.maxParallel"));
            UpdateOptions.connectTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.connectTimeoutMs"));
            UpdateOptions.readTimeoutMs = Math.max(1000, cfgMgr.getInt("performance.readTimeoutMs"));
            UpdateOptions.perDownloadTimeoutSec = Math.max(0, cfgMgr.getInt("performance.perDownloadTimeoutSec"));
            UpdateOptions.maxRetries = Math.max(1, cfgMgr.getInt("performance.maxRetries"));
            UpdateOptions.backoffBaseMs = Math.max(0, cfgMgr.getInt("performance.backoffBaseMs"));
            UpdateOptions.backoffMaxMs = Math.max(UpdateOptions.backoffBaseMs, cfgMgr.getInt("performance.backoffMaxMs"));
            UpdateOptions.maxPerHost = Math.max(1, cfgMgr.getInt("performance.maxPerHost"));
            List<Map<String, Object>> uaList =
                    (List<Map<String, Object>>) cfgMgr.getList("http.userAgents");
            UpdateOptions.userAgents.clear();
            if (uaList != null) {
                for (Map<String, Object> m : uaList) {
                    Object v = m.get("ua");
                    if (v != null) UpdateOptions.userAgents.add(v.toString());
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
        cfgMgr.addDefault("http.headers", new ArrayList<>(), "Extra headers: list of {name, value}");
        ArrayList<Map<String, String>> uas = new ArrayList<>();
        HashMap<String, String> ua1 = new HashMap<>();
        ua1.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        HashMap<String, String> ua2 = new HashMap<>();
        ua2.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        HashMap<String, String> ua3 = new HashMap<>();
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
        cfgMgr.addDefault("behavior.autoCompile.enable", false, "Enable source build fallback for GitHub");
        cfgMgr.addDefault("behavior.autoCompile.whenNoJarAsset", true, "Build when release has no jar assets");
        cfgMgr.addDefault("behavior.autoCompile.branchNewerMonths", 6, "Build when default branch is newer by N months");
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

    private Logger getLogger() {
        return logger;
    }


    public class UpdateCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("autoupdateplugins.update");
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (pluginUpdater.isUpdating()) {
                source.sendMessage(Component.text("An update is already in progress. Please wait.").color(NamedTextColor.RED));
                return;
            }
            pluginUpdater.readList(myFile, "velocity", cfgMgr.getString("updates.key"));
            source.sendMessage(Component.text("Plugins are successfully updating!").color(NamedTextColor.AQUA));
        }
    }
}
