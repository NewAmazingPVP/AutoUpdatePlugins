package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class PluginUpdater {

    private final PluginDownloader pluginDownloader;
    private final Logger logger;
    private final AtomicBoolean updating = new AtomicBoolean(false);

    public PluginUpdater(Logger logger) {
        this.logger = logger;
        pluginDownloader = new PluginDownloader(logger);
    }

    public boolean isUpdating() {
        return updating.get();
    }

    public void readList(File myFile, String platform, String key) {
        if (!updating.compareAndSet(false, true)) {
            logger.info("Update already running. Skipping new request.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (myFile.length() == 0) {
                logger.info("File is empty. Please put FileSaveName: [link to plugin]");
                return;
            }
            Yaml yaml = new Yaml();
            Map<String, String> links;
            try (FileReader reader = new FileReader(myFile)) {
                links = yaml.load(reader);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (links == null) {
                logger.info("No data in file. Aborting readList operation.");
                return;
            }
            for (Map.Entry<String, String> entry : links.entrySet()) {
                boolean downloadSuccessful = false;
                try {
                    downloadSuccessful = handleUpdateEntry(platform, key, entry);
                } catch (IOException ignored) {
                }
                if (!downloadSuccessful) {
                    logger.info("Download for " + entry.getKey() + " was not successful");
                }

            }

        }).whenComplete((v, t) -> updating.set(false));
    }

    /**
     * Update a single plugin using the same logic as {@link #readList(File, String, String)}.
     *
     * @param platform the server platform (paper, waterfall, velocity)
     * @param key      optional GitHub token
     * @param name     plugin name used for the output file
     * @param link     download link as used in list.yml
     */
    public void updatePlugin(String platform, String key, String name, String link) {
        CompletableFuture.runAsync(() -> {
            try {
                handleUpdateEntry(platform, key, new java.util.AbstractMap.SimpleEntry<>(name, link));
            } catch (IOException e) {
                logger.info("Download for " + name + " was not successful");
            }
        });
    }

    private boolean handleUpdateEntry(String platform, String key, Map.Entry<String, String> entry) throws IOException {

        try {
            logger.info(entry.getKey() + " ---- " + entry.getValue());
            String value = entry.getValue();

            boolean blobBuildPhrase = value.contains("blob.build");
            boolean busyBiscuitPhrase = value.contains("thebusybiscuit.github.io/builds");
            boolean hasSpigotPhrase = value.contains("spigotmc.org");
            boolean hasGithubPhrase = value.contains("github.com");
            boolean hasJenkinsPhrase = value.contains("https://ci.");
            boolean hasBukkitPhrase = value.contains("https://dev.bukkit.org/");
            boolean hasModrinthPhrase = value.contains("modrinth.com");
            boolean hasHangarPhrase = value.contains("https://hangar.papermc.io/");

            boolean hasAnyValidPhrase = hasSpigotPhrase || hasGithubPhrase || hasJenkinsPhrase || hasBukkitPhrase || hasModrinthPhrase || hasHangarPhrase || blobBuildPhrase || busyBiscuitPhrase;
            if (!value.endsWith("/") && hasAnyValidPhrase && !value.endsWith("]")) {
                value = entry.getValue() + "/";
            }

            if (blobBuildPhrase) {
                return handleBlobBuild(value, key, entry);
            } else if (busyBiscuitPhrase) {
                return handleBusyBiscuitDownload(value, key, entry);
            } else if (hasSpigotPhrase) {
                return handleSpigotDownload(key, entry, value);
            } else if (hasGithubPhrase) {
                return handleGitHubDownload(key, entry, value);
            } else if (hasJenkinsPhrase) {
                return handleJenkinsDownload(key, entry, value);
            } else if (hasBukkitPhrase) {
                return pluginDownloader.downloadPlugin(value + "files/latest", entry.getKey(), key);
            } else if (hasModrinthPhrase) {
                return handleModrinthDownload(platform, key, entry, value);
            } else if (hasHangarPhrase) {
                return handleHangarDownload(platform, key, entry, value);
            } else {
                return pluginDownloader.downloadPlugin(value, entry.getKey(), key);
            }
        } catch (NullPointerException ignored) {
            return false;
        }
    }

    private boolean handleBlobBuild(String value, String key, Map.Entry<String, String> entry) {
        try {
            String[] urlParts = value.split("/");
            String projectName = urlParts[urlParts.length - 2];
            String releaseChannel = urlParts[urlParts.length - 1];

            String apiUrl = String.format("https://blob.build/api/builds/%s/%s/latest", projectName, releaseChannel);

            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode response = objectMapper.readTree(connection.getInputStream());

            if (!response.get("success").asBoolean()) {
                logger.info("Failed to fetch from blob.build: " + response.get("error").asText());
                return false;
            }
            JsonNode data = response.get("data");
            String downloadUrl = data.get("fileDownloadUrl").asText();
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (Exception e) {
            logger.info("Failed to download plugin from blob.build: " + e.getMessage());
            return false;
        }
    }

    private boolean handleBusyBiscuitDownload(String value, String key, Map.Entry<String, String> entry) {

        Pattern pattern = Pattern.compile("builds/([^/]+)/([^/]+)");
        Matcher matcher = pattern.matcher(value);

        if (!matcher.find()) {
            logger.info("Invalid TheBusyBiscuit builds URL format");
            return false;
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2);

        String apiUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/builds.json", owner, repo);

        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        } catch (IOException e) {
            logger.info("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
            return false;
        }
        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

        JsonNode buildsData;
        try {
            buildsData = new ObjectMapper().readTree(connection.getInputStream());
        } catch (IOException e) {
            logger.info("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
            return false;
        }

        String lastBuild = buildsData.get("last_successful").asText();
        String downloadUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/download/%s/%s-%s.jar", owner, repo, lastBuild, repo, lastBuild);
        try {
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
            return false;
        }

    }

    private boolean handleHangarDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
        try {
            String[] parts = value.split("/");
            String projectName = parts[parts.length - 1];
            String latestVersion = getHangarLatestVersion(projectName);
            String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/versions/" + latestVersion + "/" + platform.toUpperCase() + "/download";
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from hangar, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private static @NotNull String getHangarLatestVersion(String projectName) throws IOException {
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
        return response.toString().trim();
    }

    private boolean handleModrinthDownload(String platform, String key, Map.Entry<String, String> entry, String value) {

        String[] parts = value.split("/");
        String projectName = parts[parts.length - 1];
        String apiUrl = "https://api.modrinth.com/v2/search?query=" + projectName;
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(new URL(apiUrl));
        } catch (Exception e) {
            logger.info("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }

        ArrayNode hits = (ArrayNode) rootNode.get("hits");
        JsonNode firstHit = hits.get(0);
        String projectId = firstHit.get("project_id").asText();
        String versionUrl = "https://api.modrinth.com/v2/project/" + projectId + "/version";

        ArrayNode node;
        try {
            node = (ArrayNode) objectMapper.readTree(new URL(versionUrl));
        } catch (Exception e) {
            logger.info("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }

        Optional<JsonNode> jsonNodeOptional = StreamSupport.stream(node.spliterator(), false).filter(jsonNode -> jsonNode.get("loaders").toString().toLowerCase().contains(platform)).findFirst();

        if (!jsonNodeOptional.isPresent()) {
            return false;
        }

        String downloadUrl = jsonNodeOptional.get().get("files").get(0).get("url").asText();
        try {
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private boolean handleJenkinsDownload(String key, Map.Entry<String, String> entry, String value) {

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
        if (!jenkinsLink.endsWith("/")) {
            jenkinsLink += "/";
        }

        JsonNode node;
        try {
            node = new ObjectMapper().readTree(new URL(jenkinsLink + "lastSuccessfulBuild/api/json"));
        } catch (IOException e) {
            return handleAlternateJenkinsDownload(key, entry, jenkinsLink);
        }

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

        try {
            return pluginDownloader.downloadPlugin(artifactUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from jenkins, " + value + " , are you sure link is correct and in right " + "format?" + e.getMessage());
            return false;
        }
    }

    private boolean handleGitHubDownload(String key, Map.Entry<String, String> entry, String value) {
        value = value.replace("/actions/", "/dev");
        value = value.replace("/actions", "/dev");
        if (value.contains("/dev")) {
            return handleGitHubDevDownload(key, entry, value);
        }
        String repoPath;
        int artifactNum = 1;
        String multiIdentifier = "[";

        String subString;
        if (value.contains(multiIdentifier)) {
            int startIndex = value.indexOf(multiIdentifier);
            int endIndex = value.indexOf("]", startIndex);
            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
            subString = value.substring(0, value.indexOf(multiIdentifier));
        } else {
            subString = value;
        }

        repoPath = getGitHubRepoLocation(subString);
        String apiUrl = "https://api.github.com/repos" + repoPath + "/releases";

        JsonNode node;
        try {
            node = new ObjectMapper().readTree(new URL(apiUrl));
        } catch (IOException e) {
            logger.info("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
            return false;
        }

        String downloadUrl = null;
        int times = 0;

        for (JsonNode release : node) {
            for (JsonNode asset : release.get("assets")) {
                if (asset.has("name") && asset.get("name").asText().endsWith(".jar")) {
                    times++;
                }
                if (times == artifactNum) {
                    downloadUrl = asset.get("browser_download_url").asText();
                    break;
                }
            }
            if (downloadUrl != null) break;
        }

        if (downloadUrl == null) {
            logger.info("Failed to find the specified artifact number in the release assets.");
            return false;
        }

        try {
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
            return false;
        }
    }

    private boolean handleGitHubDevDownload(String key, Map.Entry<String, String> entry, String value) {
        String repoPath;
        int artifactNum = 1;
        String multiIdentifier = "[";
        String subString;

        if (value.contains(multiIdentifier)) {
            int startIndex = value.indexOf(multiIdentifier);
            int endIndex = value.indexOf("]", startIndex);
            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
            subString = value.substring(0, value.indexOf(multiIdentifier));
        } else {
            subString = value.substring(0, value.length() - 4);
        }

        repoPath = getGitHubRepoLocation(subString);

        String apiUrl = "https://api.github.com/repos" + repoPath + "/actions/artifacts";

        JsonNode node;
        try {
            node = new ObjectMapper().readTree(new URL(apiUrl));
        } catch (IOException e) {
            logger.info("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
            return false;
        }

        String downloadUrl = null;
        int times = 0;
        for (JsonNode artifact : node.get("artifacts")) {
            if (artifact.has("name")) {
                times++;
            }
            if (times == artifactNum) {
                downloadUrl = artifact.get("archive_download_url").asText();
                break;
            }
        }

        if (downloadUrl == null) {
            logger.info("Failed to find the specified artifact number in the workflow artifacts.");
            return false;
        }

        try {
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
            return false;
        }
    }

    private boolean handleSpigotDownload(String key, Map.Entry<String, String> entry, String value) {
        try {
            String pluginId = extractPluginIdFromLink(value);
            String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (Exception e) {
            logger.info("Failed to download plugin from spigot, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private boolean handleAlternateJenkinsDownload(String key, Map.Entry<String, String> entry, String value) {
        try {
            String downloadUrl = value + "lastSuccessfulBuild/artifact/*zip*/archive.zip";
            // currently no multiartifact support for archive zip extraction jenkins
            return pluginDownloader.downloadJenkinsPlugin(downloadUrl, entry.getKey());
        } catch (Exception e) {
            logger.info("Failed to download plugin from jenkins, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private String extractPluginIdFromLink(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("(\\d+)/");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private String getGitHubRepoLocation(String inputUrl) {
        Pattern pattern = Pattern.compile("github.com(/[^/]+/[^/]+)");
        Matcher matcher = pattern.matcher(inputUrl);

        if (!matcher.find()) {
            logger.info("Repository path not found.");
            return "";
        }
        return matcher.group(1);
    }
}
