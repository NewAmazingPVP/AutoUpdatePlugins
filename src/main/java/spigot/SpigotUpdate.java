package spigot;

import org.bukkit.Bukkit;
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
    }

    public void periodUpdatePlugins(){
        int interval = config.getInt("updates.interval");
        long bootTime = config.getInt("updates.bootTime");
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run(){
                m_updatePlugins.readList(myFile);
            }
        }, bootTime, 20L * 60L * interval);
    }
}



