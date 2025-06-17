package velocity;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import common.PluginUpdater;
import velocity.AupCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

// TODO: Use velocity-plugin .json from now on instead (remove this)
@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "11.5", url = "https://www.spigotmc.org/resources/autoupdateplugins.109683/", authors = "NewAmazingPVP")
public final class VelocityUpdate {

    private PluginUpdater pluginUpdater;
    private final Toml config;
    private final ProxyServer proxy;
    private File myFile;
    private final Path dataDirectory;
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
        pluginUpdater = new PluginUpdater(Logger.getLogger("AutoUpdatePlugins"));
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
        CommandMeta updateMeta = commandManager.metaBuilder("update").plugin(this).build();
        commandManager.register(updateMeta, new UpdateCommand());

        CommandMeta aupMeta = commandManager.metaBuilder("aup").aliases("autoupdateplugins").plugin(this).build();
        commandManager.register(aupMeta, new AupCommand(pluginUpdater, myFile, config.getString("updates.key")));
    }

    public void periodUpdatePlugins() {
        long interval = config.getLong("updates.interval");
        long bootTime = config.getLong("updates.bootTime");

        proxy.getScheduler().buildTask(this, () -> pluginUpdater.readList(myFile, "velocity", config.getString("updates.key"))).delay(Duration.ofSeconds(bootTime)).repeat(Duration.ofMinutes(interval)).schedule();
    }

    private Toml loadConfig(Path path) {
        File folder = path.toFile();
        File file = new File(folder, "config.toml");
        if (file.exists()) {
            return new Toml().read(file);
        }

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
            pluginUpdater.readList(myFile, "velocity", config.getString("updates.key"));
            source.sendMessage(Component.text("Plugins are successfully updating!").color(NamedTextColor.AQUA));
        }
    }
}
