package spigot;

import org.bukkit.plugin.java.JavaPlugin;
import common.UpdatePlugins;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;

public final class SpigotUpdate extends JavaPlugin {

    private UpdatePlugins m_updatePlugins;

    @Override
    public void onEnable() {
        String spigotResourceLink = "https://www.spigotmc.org/resources/view-distance-tweaks-1-14-1-19.75164/";
        String pluginId = m_updatePlugins.extractPluginIdFromLink(spigotResourceLink);
        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
        System.out.println("Download URL: " + downloadUrl);
        m_updatePlugins.updateFloodgate(downloadUrl);
    }

    public void listYML() {
        File dataFolder = getDataFolder();

        // Create the data folder if it doesn't exist
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File myFile = new File(dataFolder, "list.yml");
        try {
            if (!myFile.exists()) {
                myFile.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void readList(String[] args) {
        Yaml yaml = new Yaml();
        try (FileReader reader = new FileReader("links.yml")) {
            Map<String, String> links = yaml.load(reader);
            for (Map.Entry<String, String> entry : links.entrySet()) {
                System.out.println(entry.getKey() + " ---- " + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}



