package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        Pattern pattern = Pattern.compile("([0-9]+)/");
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
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

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

    public void readList(File myFile, String platform) throws IOException {
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
                                System.out.println(entry.getKey() + " ---- " + entry.getValue());
                                String value = entry.getValue();
                                boolean containsPhrase = value.contains("spigotmc.org");
                                boolean githubPhrase = value.contains("github.com");
                                boolean jenkinsPhrase = value.contains("https://ci.");
                                boolean bukkitPhrase = value.contains("https://dev.bukkit.org/");
                                boolean modrinthPhrase = value.contains("modrinth.com");
                                boolean hangarPhrase = value.contains("https://hangar.papermc.io/");

                                if (!value.endsWith("/") && (containsPhrase || githubPhrase || jenkinsPhrase || bukkitPhrase || modrinthPhrase || hangarPhrase)) {
                                    if(!value.endsWith("]")) {
                                        value = entry.getValue() + "/";
                                    }
                                }
                                if (containsPhrase) {
                                    try {
                                        String pluginId = extractPluginIdFromLink(value);
                                        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
                                        updatePlugin(downloadUrl, entry.getKey());
                                    } catch (Exception e) {
                                        System.out.println("Failed to download plugin from spigot, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (githubPhrase) {
                                    try {
                                        String repoPath;
                                        int artifactNum = 1;
                                        String multiIdentifier = "[";

                                        if (value.contains(multiIdentifier)) {
                                            int startIndex = value.indexOf(multiIdentifier);
                                            int endIndex = value.indexOf("]", startIndex);
                                            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
                                            repoPath = getRepoLocation(value.substring(0, value.indexOf(multiIdentifier)));
                                        } else {
                                            repoPath = getRepoLocation(value);
                                        }

                                        String apiUrl = "https://api.github.com/repos" + repoPath + "/releases/latest";
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode node = objectMapper.readTree(new URL(apiUrl));

                                        String downloadUrl = null;
                                        int times = 0;

                                        for (JsonNode asset : node.get("assets")) {
                                            if (asset.has("name") && asset.get("name").asText().endsWith(".jar")) {
                                                times++;
                                                if (times == artifactNum) {
                                                    downloadUrl = asset.get("browser_download_url").asText();
                                                    break;
                                                }
                                            }
                                        }

                                        if (downloadUrl != null) {
                                            updatePlugin(downloadUrl, entry.getKey());
                                        } else {
                                            System.out.println("Failed to find the specified artifact number in the release assets.");
                                        }
                                    } catch (IOException | NumberFormatException e) {
                                        System.out.println("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
                                    }
                                } else if (jenkinsPhrase) {
                                    try {
                                        String jenkinsLink;
                                        int artifactNum = 1;
                                        String multiIdentifier = "[";

                                        if (value.contains(multiIdentifier)) {
                                            int startIndex = value.indexOf(multiIdentifier);
                                            int endIndex = value.indexOf("]", startIndex);
                                            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
                                            jenkinsLink = value.substring(0, value.indexOf(multiIdentifier));
                                        } else {
                                            jenkinsLink = value;
                                        }
                                        if(!jenkinsLink.endsWith("/")){
                                            jenkinsLink += "/";
                                        }
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode node = objectMapper.readTree(new URL(jenkinsLink + "lastSuccessfulBuild/api/json"));
                                        ArrayNode artifacts = (ArrayNode) node.get("artifacts");
                                        JsonNode selectedArtifact = null;
                                        int times = 0;
                                        for (JsonNode artifact : artifacts) {
                                            times++;
                                            if (times == artifactNum) {
                                                selectedArtifact = artifact;
                                                break;
                                            }
                                        }

                                        if (selectedArtifact == null && !artifacts.isEmpty()) {
                                            selectedArtifact = artifacts.get(0);
                                        }

                                        String artifactName = selectedArtifact.get("relativePath").asText();
                                        String artifactUrl = jenkinsLink + "lastSuccessfulBuild/artifact/" + artifactName;

                                        updatePlugin(artifactUrl, entry.getKey());
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from jenkins, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (bukkitPhrase) {
                                    updatePlugin(value + "files/latest", entry.getKey());
                                } else if (modrinthPhrase) {
                                    try {
                                        String[] parts = value.split("/");
                                        String projectName = parts[parts.length - 1];
                                        String apiUrl = "https://api.modrinth.com/v2/search?query=" + projectName;
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode rootNode = objectMapper.readTree(new URL(apiUrl));
                                        ArrayNode hits = (ArrayNode) rootNode.get("hits");
                                        JsonNode firstHit = hits.get(0);
                                        String projectId = firstHit.get("project_id").asText();
                                        String versionUrl = "https://api.modrinth.com/v2/project/" + projectId + "/version";
                                        ArrayNode node = (ArrayNode) objectMapper.readTree(new URL(versionUrl));
                                        for (JsonNode version : node) {
                                            if (version.get("loaders").toString().toLowerCase().contains(platform)) {
                                                String downloadUrl = version.get("files").get(0).get("url").asText();
                                                updatePlugin(downloadUrl, entry.getKey());
                                                break;
                                            }
                                        }
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (hangarPhrase){
                                    try {
                                        String[] parts = value.split("/");
                                        String projectName = parts[parts.length - 1];
                                        String apiUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/latestrelease";
                                        URL url = new URL(apiUrl);
                                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                                        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

                                        StringBuilder response = new StringBuilder();
                                        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                                            String inputLine;
                                            while ((inputLine = in.readLine()) != null) {
                                                response.append(inputLine);
                                            }
                                        }
                                        String latestVersion = response.toString().trim();
                                        String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/versions/" + latestVersion + "/" + platform.toUpperCase() + "/download";
                                        updatePlugin(downloadUrl, entry.getKey());
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from hangar, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else {
                                    updatePlugin(value, entry.getKey());
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
