package bungeecord;

import net.md_5.bungee.api.plugin.Plugin;
import common.UpdatePlugins;

import java.io.File;
import java.io.IOException;

public final class BungeeUpdate extends Plugin {

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

        if(myFile.exists()) {
            m_updatePlugins.readList(myFile);
        }
    }
}



