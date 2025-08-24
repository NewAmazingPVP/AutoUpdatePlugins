package velocity;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import common.PluginUpdater;
import velocity.AupCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "11.5", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/", authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private PluginUpdater pluginUpdater;
    private final Toml config;
    private final ProxyServer proxy;
    private File myFile;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;

    @Inject
    public VelocityUpdate(ProxyServer proxy, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        config = loadConfig(dataDirectory);
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        metricsFactory.make(this, 18455);
        pluginUpdater = new PluginUpdater(Logger.getLogger("AutoUpdatePlugins"));
        myFile = dataDirectory.resolve("list.yml").toFile();
        if (!myFile.exists()) {
            try {
                myFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        periodUpdatePlugins();
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta updateMeta = commandManager.metaBuilder("update").plugin(this).build();
        commandManager.register(updateMeta, new UpdateCommand());

        CommandMeta aupMeta = commandManager.metaBuilder("aup").aliases("autoupdateplugins").plugin(this).build();
        commandManager.register(aupMeta, new AupCommand(pluginUpdater, myFile, config.getString("updates.key")));

        applyHttpConfig();
        applyBehaviorConfig();
    }

    public void periodUpdatePlugins() {
        long interval = config.getLong("updates.interval");
        long bootTime = config.getLong("updates.bootTime");

        proxy.getScheduler().buildTask(this, () -> pluginUpdater.readList(myFile, "velocity", config.getString("updates.key"))).delay(Duration.ofSeconds(bootTime)).repeat(Duration.ofMinutes(interval)).schedule();
    }

    private Toml loadConfig(Path path) {
        File folder = path.toFile();
        File file = new File(folder, "config.toml");
        if (file.exists()) {
            return new Toml().read(file);
        }

        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        try (InputStream input = getClass().getResourceAsStream("/" + file.getName())) {
            if (input != null) {
                Files.copy(input, file.toPath());
            } else {
                file.createNewFile();
            }
        } catch (IOException exception) {
            exception.printStackTrace();
            return null;
        }
        return new Toml().read(file);
    }

    @SuppressWarnings("unchecked")
    private void applyHttpConfig() {
        try {
            String userAgent = config.getString("http.userAgent");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            java.util.List<Object> list = (java.util.List<Object>) config.getList("http.headers");
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof java.util.Map) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                        Object n = m.get("name");
                        Object v = m.get("value");
                        if (n != null && v != null) headers.put(n.toString(), v.toString());
                    }
                }
            }
            common.PluginDownloader.setHttpHeaders(headers, userAgent);
        } catch (Throwable ignored) {}

        try {
            String type = optString(config, "proxy.type", "DIRECT");
            String host = optString(config, "proxy.host", "");
            long p = config.getLong("proxy.port");
            int port = (p <= 0 || p > Integer.MAX_VALUE) ? 0 : (int) p;
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
        } catch (Throwable ignored) {}
    }

    private void applyBehaviorConfig() {
        try {
            common.UpdateOptions.zipFileCheck = optBool(config, "behavior.zipFileCheck", true);
            common.UpdateOptions.ignoreDuplicates = optBool(config, "behavior.ignoreDuplicates", true);
            common.UpdateOptions.allowPreReleaseDefault = optBool(config, "behavior.allowPreRelease", false);
            common.UpdateOptions.autoCompileEnable = optBool(config, "behavior.autoCompile.enable", true);
            common.UpdateOptions.autoCompileWhenNoJarAsset = optBool(config, "behavior.autoCompile.whenNoJarAsset", true);
            common.UpdateOptions.autoCompileBranchNewerMonths = (int) optLong(config, "behavior.autoCompile.branchNewerMonths", 4);
            common.UpdateOptions.debug = optBool(config, "behavior.debug", false);

            common.UpdateOptions.tempPath = emptyToNull(optString(config, "paths.tempPath", ""));
            common.UpdateOptions.updatePath = emptyToNull(optString(config, "paths.updatePath", ""));
            common.UpdateOptions.filePath = emptyToNull(optString(config, "paths.filePath", ""));

            common.UpdateOptions.maxParallel = Math.max(1, (int) optLong(config, "performance.maxParallel", 4));
            common.UpdateOptions.connectTimeoutMs = Math.max(1000, (int) optLong(config, "performance.connectTimeoutMs", 10000));
            common.UpdateOptions.readTimeoutMs = Math.max(1000, (int) optLong(config, "performance.readTimeoutMs", 30000));
            common.UpdateOptions.perDownloadTimeoutSec = Math.max(0, (int) optLong(config, "performance.perDownloadTimeoutSec", 0));
            common.UpdateOptions.maxRetries = Math.max(1, (int) optLong(config, "performance.maxRetries", 4));
            common.UpdateOptions.backoffBaseMs = Math.max(0, (int) optLong(config, "performance.backoffBaseMs", 500));
            common.UpdateOptions.backoffMaxMs = Math.max(common.UpdateOptions.backoffBaseMs, (int) optLong(config, "performance.backoffMaxMs", 5000));

            java.util.List<String> uas = new java.util.ArrayList<>();
            java.util.List<Object> raw = (java.util.List<Object>) config.getList("http.userAgents");
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof String) uas.add((String) o);
                    else if (o != null) uas.add(o.toString());
                }
            }
            common.UpdateOptions.userAgents.clear();
            common.UpdateOptions.userAgents.addAll(uas);
        } catch (Throwable ignored) {}
    }

    private String emptyToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s; }
    private boolean optBool(Toml t, String path, boolean def) {
        try { Boolean v = t.getBoolean(path); return v != null ? v : def; } catch (Throwable e) { return def; }
    }
    private long optLong(Toml t, String path, long def) {
        try { Long v = t.getLong(path); return v != null ? v : def; } catch (Throwable e) { return def; }
    }
    private String optString(Toml t, String path, String def) {
        try { String v = t.getString(path); return v != null ? v : def; } catch (Throwable e) { return def; }
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
            pluginUpdater.readList(myFile, "velocity", config.getString("updates.key"));
            source.sendMessage(Component.text("Plugins are successfully updating!").color(NamedTextColor.AQUA));
        }
    }
}
