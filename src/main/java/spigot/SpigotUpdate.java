package spigot;

import common.PluginUpdater;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import spigot.AupCommand;

import java.io.File;
import java.io.IOException;

public final class SpigotUpdate extends JavaPlugin {

    private PluginUpdater pluginUpdater;
    private File myFile;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        new Metrics(this, 18454);
        pluginUpdater = new PluginUpdater(this.getLogger());
        config = getConfig();
        saveDefaultConfig();
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
            AupCommand aup = new AupCommand(pluginUpdater, myFile, config);
            getCommand("aup").setExecutor(aup);
            getCommand("aup").setTabCompleter(aup);
        }
    }

    public void periodUpdatePlugins() {
        int interval = config.getInt("updates.interval");
        long bootTime = config.getInt("updates.bootTime");
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> pluginUpdater.readList(myFile, "paper", config.getString("updates.key")), bootTime * 20L, 20L * 60L * interval);
    }

    public class UpdateCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            pluginUpdater.readList(myFile, "paper", config.getString("updates.key"));
            sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
            return true;
        }
    }
}



