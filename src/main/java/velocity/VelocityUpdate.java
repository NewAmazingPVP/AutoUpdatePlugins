package velocity;

import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import common.UpdatePlugins;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "9.0", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/", authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private UpdatePlugins m_updatePlugins;
    private Toml config;
    private ProxyServer proxy;
    private File myFile;
    private Path dataDirectory;
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
        m_updatePlugins = new UpdatePlugins();
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
        CommandMeta commandMeta = commandManager.metaBuilder("update")
                .plugin(this)
                .build();

        SimpleCommand simpleCommand = new UpdateCommand();
        commandManager.register(commandMeta, simpleCommand);
    }

    public void periodUpdatePlugins() {
        long interval = config.getLong("updates.interval");
        long bootTime = config.getLong("updates.bootTime");

        proxy.getScheduler().buildTask(this, () -> {
            try {
                m_updatePlugins.readList(myFile, "velocity", config.getString("updates.key"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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

    public class UpdateCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("autoupdateplugins.update");
        }
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            try {
                m_updatePlugins.readList(myFile, "velocity", config.getString("updates.key"));
                source.sendMessage(Component.text("Plugins are successfully updating!").color(NamedTextColor.AQUA));
            } catch (IOException e) {
                source.sendMessage(Component.text("Plugins failed to update!").color(NamedTextColor.RED));
                throw new RuntimeException(e);
            }
        }
    }
}
