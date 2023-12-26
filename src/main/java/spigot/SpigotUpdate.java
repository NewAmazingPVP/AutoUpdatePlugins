package spigot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import common.UpdatePlugins;

import java.io.File;
import java.io.IOException;

public final class SpigotUpdate extends JavaPlugin {

    private UpdatePlugins m_updatePlugins;
    private File myFile;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        new Metrics(this, 18454);
        m_updatePlugins = new UpdatePlugins();
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
        getCommand("update").setExecutor(new updateCommand());
    }

    public void periodUpdatePlugins(){
        int interval = config.getInt("updates.interval");
        long bootTime = config.getInt("updates.bootTime");
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run(){
                try {
                    m_updatePlugins.readList(myFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, bootTime*20L, 20L * 60L * interval);
    }

    public class updateCommand implements CommandExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            try {
                m_updatePlugins.readList(myFile);
                sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
                return true;
            } catch (IOException e) {
                sender.sendMessage(ChatColor.RED + "Plugins failed to update!");
                throw new RuntimeException(e);
            }
        }
    }
}



