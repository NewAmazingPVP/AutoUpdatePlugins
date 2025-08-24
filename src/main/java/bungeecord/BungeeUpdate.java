package bungeecord;

import common.PluginUpdater;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import bungeecord.AupCommand;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public final class BungeeUpdate extends Plugin {

    private PluginUpdater pluginUpdater;
    private File myFile;
    private Configuration config;

    @Override
    public void onEnable() {
        new Metrics(this, 18456);
        pluginUpdater = new PluginUpdater(this.getLogger());

        saveDefaultConfig();
        loadConfiguration();
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
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new AupCommand(pluginUpdater, myFile, config));
        applyHttpConfig();
        applyBehaviorConfig();
    }

    public void periodUpdatePlugins() {
        int interval = config.getInt("updates.interval") * 60;
        long bootTime = config.getInt("updates.bootTime");
        getProxy().getScheduler().schedule(this, () -> getProxy().getScheduler().runAsync(this, () -> pluginUpdater.readList(myFile, "waterfall", config.getString("updates.key"))), bootTime, interval, TimeUnit.SECONDS);
    }

    private void saveDefaultConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (file.exists()) {
            return;
        }
        file.getParentFile().mkdirs();
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void loadConfiguration() {
        File file = new File(getDataFolder(), "config.yml");
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyHttpConfig() {
        try {
            String userAgent = config.getString("http.userAgent");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (config.get("http.headers") instanceof java.util.List) {
                java.util.List<Object> list = (java.util.List<Object>) config.get("http.headers");
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
            }
            common.PluginDownloader.setHttpHeaders(headers, userAgent);
        } catch (Throwable ignored) {}

        try {
            String type = config.getString("proxy.type", "DIRECT");
            String host = config.getString("proxy.host", "");
            int port = config.getInt("proxy.port", 0);
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
            common.UpdateOptions.zipFileCheck = config.getBoolean("behavior.zipFileCheck", true);
            common.UpdateOptions.ignoreDuplicates = config.getBoolean("behavior.ignoreDuplicates", true);
            common.UpdateOptions.allowPreReleaseDefault = config.getBoolean("behavior.allowPreRelease", false);
            common.UpdateOptions.autoCompileEnable = config.getBoolean("behavior.autoCompile.enable", true);
            common.UpdateOptions.autoCompileWhenNoJarAsset = config.getBoolean("behavior.autoCompile.whenNoJarAsset", true);
            common.UpdateOptions.autoCompileBranchNewerMonths = config.getInt("behavior.autoCompile.branchNewerMonths", 4);
            common.UpdateOptions.debug = config.getBoolean("behavior.debug", false);

            common.UpdateOptions.tempPath = emptyToNull(config.getString("paths.tempPath", ""));
            common.UpdateOptions.updatePath = emptyToNull(config.getString("paths.updatePath", ""));
            common.UpdateOptions.filePath = emptyToNull(config.getString("paths.filePath", ""));

            common.UpdateOptions.maxParallel = Math.max(1, config.getInt("performance.maxParallel", 4));
            common.UpdateOptions.connectTimeoutMs = Math.max(1000, config.getInt("performance.connectTimeoutMs", 10000));
            common.UpdateOptions.readTimeoutMs = Math.max(1000, config.getInt("performance.readTimeoutMs", 30000));
            common.UpdateOptions.perDownloadTimeoutSec = Math.max(0, config.getInt("performance.perDownloadTimeoutSec", 0));
            common.UpdateOptions.maxRetries = Math.max(1, config.getInt("performance.maxRetries", 4));
            common.UpdateOptions.backoffBaseMs = Math.max(0, config.getInt("performance.backoffBaseMs", 500));
            common.UpdateOptions.backoffMaxMs = Math.max(common.UpdateOptions.backoffBaseMs, config.getInt("performance.backoffMaxMs", 5000));

            java.util.List<String> uas = new java.util.ArrayList<>();
            if (config.get("http.userAgents") instanceof java.util.List) {
                java.util.List<Object> list = (java.util.List<Object>) config.get("http.userAgents");
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof java.util.Map) {
                            Object v = ((java.util.Map<?, ?>) o).get("ua");
                            if (v != null) uas.add(v.toString());
                        } else if (o != null) {
                            uas.add(o.toString());
                        }
                    }
                }
            }
            common.UpdateOptions.userAgents.clear();
            common.UpdateOptions.userAgents.addAll(uas);
        } catch (Throwable ignored) {}
    }

    private String emptyToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s; }

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
            pluginUpdater.readList(myFile, "waterfall", config.getString("updates.key"));
            sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
        }
    }
}



