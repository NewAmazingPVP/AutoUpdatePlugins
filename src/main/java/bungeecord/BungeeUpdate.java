package bungeecord;

import common.ConfigManager;
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
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new AupCommand(pluginUpdater, myFile, cfgMgr));
    }

    public void periodUpdatePlugins() {
        int interval = cfgMgr.getInt("updates.interval");
        long bootTime = cfgMgr.getInt("updates.bootTime");
        getProxy().getScheduler().schedule(this, () -> getProxy().getScheduler().runAsync(this, () -> pluginUpdater.readList(myFile, "waterfall", cfgMgr.getString("updates.key"))), bootTime, interval, TimeUnit.SECONDS);
    }

    private void generateOrUpdateConfig() {
        cfgMgr.addDefault("updates.interval", 120, "Time between plugin updates in minutes");
        cfgMgr.addDefault("updates.bootTime", 50, "Delay in seconds after server startup before updating");
        cfgMgr.addDefault("updates.key", "", "GitHub token for Actions/authenticated requests (optional)");

        cfgMgr.addDefault("http.userAgent", "", "HTTP User-Agent override (leave blank to auto-rotate)");
        cfgMgr.addDefault("http.headers", new java.util.ArrayList<>(), "Extra headers: list of {name, value}");
        java.util.ArrayList<java.util.Map<String, String>> uas = new java.util.ArrayList<>();
        java.util.HashMap<String, String> ua1 = new java.util.HashMap<>(); ua1.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36");
        java.util.HashMap<String, String> ua2 = new java.util.HashMap<>(); ua2.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        java.util.HashMap<String, String> ua3 = new java.util.HashMap<>(); ua3.put("ua", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/126.0");
        uas.add(ua1); uas.add(ua2); uas.add(ua3);
        cfgMgr.addDefault("http.userAgents", uas, "Optional pool of User-Agents; rotates on retry");

        cfgMgr.addDefault("proxy.type", "DIRECT", "Proxy type: DIRECT | HTTP | SOCKS");
        cfgMgr.addDefault("proxy.host", "127.0.0.1", "Proxy host");
        cfgMgr.addDefault("proxy.port", 7890, "Proxy port");

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



