package bungeecord;

import org.bukkit.plugin.java.JavaPlugin;
import common.UpdatePlugins;

public final class BungeeUpdate extends JavaPlugin {

    private UpdatePlugins m_updatePlugins;

    @Override
    public void onEnable() {
        String spigotResourceLink = "https://www.spigotmc.org/resources/view-distance-tweaks-1-14-1-19.75164/";
        String pluginId = m_updatePlugins.extractPluginIdFromLink(spigotResourceLink);
        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
        System.out.println("Download URL: " + downloadUrl);
        m_updatePlugins.updateFloodgate(downloadUrl);
    }
}



