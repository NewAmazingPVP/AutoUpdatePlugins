package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    public String getRepoLocation(String inputUrl) throws MalformedURLException {
        URL url = new URL(inputUrl);
        Pattern pattern = Pattern.compile("^/([^/]+)/([^/]+)");
        Matcher matcher = pattern.matcher(url.getPath());

        if (matcher.find()) {
            return matcher.group(0);
        } else {
            System.out.println("Repository path not found.");
            return "";
        }
    }

    public void updatePlugin(String link, String fileName) {
        String outputFilePath = "plugins/" + fileName + ".jar";

        try {
            URL url = new URL(link);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            String domain = url.getHost();
            if (domain.contains("github.com")) {
                connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
            } else if (domain.contains("spiget.org")) {
                connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
            }

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFilePath)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } catch (IOException e) {
            System.out.println("Failed to download plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void readList(File myFile) throws IOException {
        CompletableFuture.runAsync(() -> {
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
                                boolean githubPhrase = entry.getValue().contains("github.com");
                                boolean jenkinsPhrase = entry.getValue().contains("https://ci.");
                                boolean bukkitPhrase = entry.getValue().contains("https://dev.bukkit.org/");
                                if (containsPhrase) {
                                    try {
                                        String spigotResourceLink = entry.getValue();
                                        String pluginId = extractPluginIdFromLink(spigotResourceLink);
                                        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
                                        updatePlugin(downloadUrl, entry.getKey());
                                    } catch (Exception e) {
                                        System.out.println("Failed to download plugin from spigot, " + entry.getValue() + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (githubPhrase) {
                                    try {
                                        String inputUrl = entry.getValue();
                                        String repoPath = getRepoLocation(inputUrl);
                                        String apiUrl = "https://api.github.com/repos" + repoPath + "/releases/latest";
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode node = objectMapper.readTree(new URL(apiUrl));

                                        String downloadUrl = null;
                                        for (JsonNode asset : node.get("assets")) {
                                            if (asset.has("name") && asset.get("name").asText().endsWith(".jar")) {
                                                downloadUrl = asset.get("browser_download_url").asText();
                                                break;
                                            }
                                        }
                                        updatePlugin(downloadUrl, entry.getKey());
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from github, " + entry.getValue() + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }

                                } else if (jenkinsPhrase){
                                    try {
                                        String pluginLink = entry.getValue();
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode node = objectMapper.readTree(new URL(pluginLink + "lastSuccessfulBuild/api/json"));
                                        String artifactName = node.get("artifacts").get(0).get("relativePath").asText();
                                        String artifactUrl = pluginLink + "lastSuccessfulBuild/artifact/" + artifactName;

                                        updatePlugin(artifactUrl, entry.getKey());
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from jenkins, " + entry.getValue() + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (bukkitPhrase){
                                    updatePlugin(entry.getValue() + "files/latest", entry.getKey());
                                } else {
                                    updatePlugin(entry.getValue(), entry.getKey());
                                }
                            } catch (NullPointerException ignored) {
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}


