package newamazingpvp.autoupdateplugins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Autoupdateplugins extends JavaPlugin {

    @Override
    public void onEnable() {
        String spigotResourceLink = "https://www.spigotmc.org/resources/view-distance-tweaks-1-14-1-19.75164/";
        String pluginId = extractPluginIdFromLink(spigotResourceLink);
        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
        System.out.println("Download URL: " + downloadUrl);
        updateFloodgate(downloadUrl);
    }


    private String fetchJsonResponse(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            connection.disconnect();
            return content.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private String extractFileUrl(String jsonResponse) {
        JsonElement jsonElement = JsonParser.parseString(jsonResponse);
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        JsonObject fileObject = jsonObject.getAsJsonObject("file");
        return fileObject.get("url").getAsString();
    }


    private String extractPluginIdFromLink(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("\\.([0-9]+)/");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }

    public void updateFloodgate(String link) {
        String latestVersionUrl;
        latestVersionUrl = link;
        String outputFilePath = "plugins/Floodgate.jar";

        try (InputStream in = new URL(latestVersionUrl).openStream();
             FileOutputStream out = new FileOutputStream(outputFilePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException ignored) {
        }
    }
}
