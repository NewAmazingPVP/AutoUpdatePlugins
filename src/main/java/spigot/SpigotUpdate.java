package spigot;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import common.UpdatePlugins;

import java.io.File;
import java.io.IOException;

public final class SpigotUpdate extends JavaPlugin {

    private UpdatePlugins m_updatePlugins;
    private File myFile;

    @Override
    public void onEnable() {
        m_updatePlugins = new UpdatePlugins();
        File dataFolder = getDataFolder();

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

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
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run(){
                m_updatePlugins.readList(myFile);
            }
        }, 0L, 20L * 60L * 1);
    }
}



