package velocity;

import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import common.UpdatePlugins;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Plugin(id = "autoupdateplugins",name = "AutoUpdatePlugins",version = "2.0", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/",authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private UpdatePlugins m_updatePlugins;
    private Toml config;
    private ProxyServer proxy;
    private File myFile;
    @Inject
    public VelocityUpdate(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        config = loadConfig(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        m_updatePlugins = new UpdatePlugins();
        File dataFolder = proxy.
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
        long interval = config.getLong("updates.interval");
        long bootTime = config.getLong("updates.bootTime");

        proxy.getScheduler().buildTask(this, () -> {
            m_updatePlugins.readList(myFile);
        }).delay(Duration.ofSeconds(bootTime)).repeat(Duration.ofMinutes(interval)).schedule();
    }

    private Toml loadConfig(Path path) {
        File folder = path.toFile();
        File file = new File(folder, "config.toml");

        if (!file.exists()) {
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
        }
        return new Toml().read(file);
    }

}