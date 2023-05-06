package common;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdatePlugins {

    public String extractPluginIdFromLink(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("\\.([0-9]+)/");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }

    public void updatePlugin(String link, String fileName) {
        String outputFilePath = "plugins/" + fileName + ".jar";

        try (InputStream in = new URL(link).openStream();
             FileOutputStream out = new FileOutputStream(outputFilePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException ignored) {
        }
    }

    public void readList(File myFile) {
        if (myFile.length() == 0) {
            System.out.println("File is empty. Please put FileSaveName: [link to plugin]");
        } else {
            Yaml yaml = new Yaml();
            try (FileReader reader = new FileReader(myFile)) {
                Map<String, String> links = yaml.load(reader);
                if (links == null) {
                    System.out.println("No data in file. Aborting readList operation.");
                } else {
                    for (Map.Entry<String, String> entry : links.entrySet()) {
                        try {
                            System.out.println((entry.getKey() + " ---- " + entry.getValue()));
                            boolean containsPhrase = entry.getValue().contains("spigotmc.org");
                            if(!containsPhrase)
                            {
                                updatePlugin(entry.getValue(), entry.getKey());
                            } else {
                                String spigotResourceLink = entry.getValue();
                                String pluginId = extractPluginIdFromLink(spigotResourceLink);
                                String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
                                updatePlugin(downloadUrl, entry.getKey());
                            }
                        } catch (NullPointerException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

