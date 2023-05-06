package bungeecord;

import net.md_5.bungee.api.plugin.Plugin;
import common.UpdatePlugins;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public final class BungeeUpdate extends Plugin {

    private UpdatePlugins m_updatePlugins;
    private File myFile;
    private Configuration config;

    @Override
    public void onEnable() {
        m_updatePlugins = new UpdatePlugins();
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
    }

    public void periodUpdatePlugins(){
        int interval = config.getInt("updates.interval") * 60;
        long bootTime = config.getInt("updates.bootTime");
        long bootDelay = (long) (bootTime * 0.05);
        getProxy().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run(){
                m_updatePlugins.readList(myFile);
            }
        }, bootDelay, interval, TimeUnit.SECONDS);
    }

    private void saveDefaultConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                Files.copy(in, file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
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
}



