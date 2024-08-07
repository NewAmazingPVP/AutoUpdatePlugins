package bungeecord;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
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
        new Metrics(this, 18456);
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
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new UpdateCommand());
    }

    public void periodUpdatePlugins(){
        int interval = config.getInt("updates.interval") * 60;
        long bootTime = config.getInt("updates.bootTime");

        getProxy().getScheduler().schedule(this, () -> {
            getProxy().getScheduler().runAsync(this, () -> {
                try {
                    m_updatePlugins.readList(myFile, "waterfall", config.getString("updates.key"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }, bootTime, interval, TimeUnit.SECONDS);
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

    public class UpdateCommand extends Command {

        public UpdateCommand() {
            super("update", "autoupdateplugins.update");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            try {
                m_updatePlugins.readList(myFile, "waterfall", config.getString("updates.key"));
                sender.sendMessage(ChatColor.AQUA + "Plugins are successfully updating!");
            } catch (IOException e) {
                sender.sendMessage(ChatColor.RED + "Plugins failed to update!");
                throw new RuntimeException(e);
            }
        }
    }
}



