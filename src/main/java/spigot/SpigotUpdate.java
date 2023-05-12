package spigot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import common.UpdatePlugins;
import org.inventivetalent.update.spiget.SpigetUpdate;
import org.inventivetalent.update.spiget.UpdateCallback;
import org.inventivetalent.update.spiget.comparator.VersionComparator;

import java.io.File;
import java.io.IOException;

public final class SpigotUpdate extends JavaPlugin {

    private UpdatePlugins m_updatePlugins;
    private File myFile;
    private FileConfiguration config;
    private final File pluginFolder = new File(getDataFolder().getParentFile(), "plugins");

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
        checkForUpdates();
    }

    public void periodUpdatePlugins(){
        int interval = config.getInt("updates.interval");
        long bootTime = config.getInt("updates.bootTime");
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run(){
                m_updatePlugins.readList(myFile);
            }
        }, bootTime, 20L * 60L * interval);
    }

    private void checkForUpdates() {
        for (File file : pluginFolder.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                SpigetUpdate updater = new SpigetUpdate(this, 334543);
                updater.checkForUpdate(new UpdateCallback() {
                    @Override
                    public void updateAvailable(String newVersion, String downloadUrl, boolean hasDirectDownload) {
                        if (hasDirectDownload) {
                            getLogger().info("Updating " + file.getName() + " to version " + newVersion);
                            if (updater.downloadUpdate()) {
                                getLogger().info("Update for " + file.getName() + " downloaded successfully");
                            } else {
                                getLogger().warning("Update for " + file.getName() + " failed: " + updater.getFailReason());
                            }
                        }
                    }

                    @Override
                    public void upToDate() {
                        getLogger().info(file.getName() + " is up-to-date");
                    }
                });
            }
        }
    }



    private int getPluginId(File file) {
        String name = file.getName();
        int index = name.indexOf('-');
        if (index > 0) {
            try {
                return Integer.parseInt(name.substring(0, index));
            } catch (NumberFormatException e) {
            }
        }
        return -1;
    }
}



