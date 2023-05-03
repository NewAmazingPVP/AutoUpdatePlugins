package common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdatePlugins {
    private String fileLoc;
    public String fetchJsonResponse(String apiUrl) {
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


    public String extractPluginIdFromLink(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("\\.([0-9]+)/");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }

    public String fileLocationSaveName(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("\\resources/([a-z]-).");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (matcher.find()) {
            fileLoc = matcher.group(1);
        }
    }

    public void updateFloodgate(String link) {
        String outputFilePath = "plugins/" + fileLoc;

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
}

