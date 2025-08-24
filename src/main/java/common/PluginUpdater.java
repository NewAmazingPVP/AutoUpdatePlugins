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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
            if (common.UpdateOptions.debug) {
                logger.info("[DEBUG] Starting update run: entries=" + links.size() + ", parallel=" + Math.max(1, common.UpdateOptions.maxParallel));
            }
            int parallel = Math.max(1, common.UpdateOptions.maxParallel);
            ExecutorService ex = createExecutor(parallel);
            java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(parallel);
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, String> entry : links.entrySet()) {
                futures.add(ex.submit(() -> {
                    boolean ok = false;
                    try {
                        sem.acquire();
                        ok = handleUpdateEntry(platform, key, entry);
                    } catch (IOException ignored) {
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        sem.release();
                    }
                    if (!ok) {
                        if (common.UpdateOptions.debug) logger.info("[DEBUG] Download failed for " + entry.getKey() + " -> " + entry.getValue());
                        else logger.info("Download for " + entry.getKey() + " was not successful");
                    }
                }));
            }
            ex.shutdown();
            try {
                int cap = common.UpdateOptions.perDownloadTimeoutSec;
                if (cap > 0) {
                    ex.awaitTermination(cap, TimeUnit.SECONDS);
                } else {
                    ex.awaitTermination(7, TimeUnit.DAYS);
                }
            } catch (InterruptedException ignored) {}

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
            ExecutorService ex = createExecutor(1);
            try {
                ex.submit(() -> {
                    try { handleUpdateEntry(platform, key, new java.util.AbstractMap.SimpleEntry<>(name, link)); }
                    catch (IOException e) { logger.info("Download for " + name + " was not successful"); }
                }).get();
            } catch (Exception ignored) {
            } finally {
                ex.shutdownNow();
            }
        });
    }

    private static Path decideInstallPath(String pluginName) {
        Path pluginsDir = Paths.get("plugins");
        Path mainJar     = pluginsDir.resolve(pluginName + ".jar");

        if (UpdateOptions.useUpdateFolder) {
            Path updateJar   = pluginsDir.resolve("update").resolve(pluginName + ".jar");
            try { Files.createDirectories(updateJar.getParent()); } catch (Exception ignored) {}
            return Files.exists(mainJar) ? updateJar : mainJar;
        } else {
            return mainJar;
        }
    }

    private ExecutorService createExecutor(int parallelism) {
        try {
            Class<?> execs = Class.forName("java.util.concurrent.Executors");
            java.lang.reflect.Method m = execs.getMethod("newVirtualThreadPerTaskExecutor");
            Object svc = m.invoke(null);
            return (ExecutorService) svc;
        } catch (Throwable ignore) {
            int p = Math.max(1, parallelism);
            int cores = Runtime.getRuntime().availableProcessors();
            p = Math.min(Math.max(1, p), Math.max(2, cores));
            return Executors.newFixedThreadPool(p);
        }
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
            boolean hasGuizhanssPhrase = value.contains("builds.guizhanss.com");
            boolean hasMineBBSPhrase = value.contains("minebbs.com");
            boolean hasCurseForgePhrase = value.contains("curseforge.com");

            boolean hasAnyValidPhrase = hasSpigotPhrase || hasGithubPhrase || hasJenkinsPhrase || hasBukkitPhrase || hasModrinthPhrase || hasHangarPhrase || blobBuildPhrase || busyBiscuitPhrase || hasGuizhanssPhrase || hasMineBBSPhrase || hasCurseForgePhrase;
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
            } else if (hasGuizhanssPhrase) {
                return handleGuizhanssDownload(value, key, entry);
            } else if (hasMineBBSPhrase) {
                return handleMineBbsDownload(value, key, entry);
            } else if (hasCurseForgePhrase) {
                return handleCurseForgeDownload(value, key, entry);
            } else {
                try {
                    if (pluginDownloader.downloadPlugin(value, entry.getKey(), key)) return true;
                } catch (IOException ignored) {}
                return handleGenericPageDownload(value, key, entry);
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

        String getRegex = null;
        int qIndexMod = value.indexOf('?');
        if (qIndexMod != -1) {
            getRegex = queryParam(value.substring(qIndexMod + 1), "get");
        }

        Optional<JsonNode> jsonNodeOptional = StreamSupport.stream(node.spliterator(), false)
                .filter(jsonNode -> jsonNode.get("loaders").toString().toLowerCase().contains(platform))
                .findFirst();

        if (!jsonNodeOptional.isPresent()) {
            return false;
        }

        JsonNode filesArray = jsonNodeOptional.get().get("files");
        String downloadUrl = null;
        if (getRegex != null) {
            for (JsonNode f : filesArray) {
                if (f.has("filename") && f.get("filename").asText().matches(getRegex)) {
                    downloadUrl = f.get("url").asText();
                    break;
                }
            }
        }
        if (downloadUrl == null) {
            downloadUrl = filesArray.get(0).get("url").asText();
        }
        try {
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (IOException e) {
            logger.info("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private boolean handleGuizhanssDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            String[] parts = value.split("/");
            String owner = parts[parts.length - 2];
            String repo = parts[parts.length - 1];
            String api = String.format("https://builds.guizhanss.com/%s/%s/master/builds.json", owner, repo);
            JsonNode data = new ObjectMapper().readTree(new URL(api));
            String last = data.get("last_successful").asText();
            String jarName = repo + "-" + last + ".jar";
            String downloadUrl = String.format("https://builds.guizhanss.com/%s/%s/master/download/%s/%s", owner, repo, last, jarName);
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (Exception e) {
            logger.info("Failed to download from guizhanss builds: " + e.getMessage());
            return false;
        }
    }

    private boolean handleMineBbsDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            Document doc = Jsoup.connect(value).userAgent("AutoUpdatePlugins").get();
            Elements links = doc.select("a[href]");
            String target = null;
            for (Element a : links) {
                String href = a.attr("abs:href");
                if (href.endsWith(".jar") || href.contains("github.com") || href.contains("modrinth.com") || href.contains("spigotmc.org") || href.contains("hangar.papermc.io") || href.contains("download")) {
                    target = href;
                    break;
                }
            }
            if (target == null) return false;
            if (target.endsWith(".jar")) {
                return pluginDownloader.downloadPlugin(target, entry.getKey(), key);
            }
            return handleUpdateEntry("paper", key, new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), target));
        } catch (Exception e) {
            logger.info("Failed to parse MineBBS page: " + e.getMessage());
            return false;
        }
    }

    private boolean handleCurseForgeDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            Document doc = Jsoup.connect(value).userAgent("AutoUpdatePlugins").get();
            Elements anchors = doc.select("a[href]");
            String filesPage = null;
            for (Element a : anchors) {
                String href = a.attr("abs:href");
                if (href.contains("/files")) { filesPage = href; break; }
            }
            String downloadUrl = null;
            if (filesPage != null) {
                Document files = Jsoup.connect(filesPage).userAgent("AutoUpdatePlugins").get();
                for (Element a : files.select("a[href]")) {
                    String href = a.attr("abs:href");
                    if (href.endsWith("/download")) { downloadUrl = href; break; }
                }
            }
            if (downloadUrl == null) {
                for (Element a : anchors) {
                    String href = a.attr("abs:href");
                    if (href.endsWith("/download")) { downloadUrl = href; break; }
                }
            }
            if (downloadUrl == null) return false;
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
        } catch (Exception e) {
            logger.info("Failed to parse CurseForge page: " + e.getMessage());
            return false;
        }
    }

    private boolean handleGenericPageDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(value).openConnection();
                conn.setRequestProperty("User-Agent", "AutoUpdatePlugins");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(common.UpdateOptions.connectTimeoutMs);
                conn.setReadTimeout(common.UpdateOptions.readTimeoutMs);
                int code = conn.getResponseCode();
                String ct = conn.getContentType();
                String cd = conn.getHeaderField("Content-Disposition");
                boolean indicatesBinary = (ct != null && !ct.startsWith("text/") && !ct.contains("xml"))
                        || (cd != null && cd.toLowerCase().contains(".jar"));
                if (code >= 200 && code < 300 && indicatesBinary) {
                    return pluginDownloader.downloadPlugin(value, entry.getKey(), key);
                }
            } catch (IOException ignored) {}

            Document doc = Jsoup.connect(value).userAgent("AutoUpdatePlugins").get();
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (href.endsWith(".jar")) {
                    return pluginDownloader.downloadPlugin(href, entry.getKey(), key);
                }
                if (href.contains("github.com") || href.contains("modrinth.com") || href.contains("spigotmc.org") || href.contains("hangar.papermc.io") || href.contains("builds.guizhanss.com")) {
                    return handleUpdateEntry("paper", key, new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), href));
                }
            }
            return false;
        } catch (Exception e) {
            logger.info("Failed to parse page: " + e.getMessage());
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
        String query = null;
        int qIndex = value.indexOf('?');
        if (qIndex != -1) {
            query = value.substring(qIndex + 1);
        }
        String getRegexJ = queryParam(query, "get");
        JsonNode selectedArtifact = null;
        int times = 0;
        for (JsonNode artifact : artifacts) {
            times++;
            if (getRegexJ != null && artifact.has("fileName") && artifact.get("fileName").asText().matches(getRegexJ)) {
                selectedArtifact = artifact;
                break;
            }
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
        value = value.replace("/actions/", "/dev").replace("/actions", "/dev");
        if (value.contains("/dev")) {
            return handleGitHubDevDownload(key, entry, value);
        }

        try {
            String query = null;
            int qIdx = value.indexOf('?');
            if (qIdx != -1) { query = value.substring(qIdx + 1); value = value.substring(0, qIdx); }

            int artifactNum = 1;
            int lb = value.indexOf('['), rb = value.indexOf(']', lb + 1);
            String repoUrl = (lb != -1 && rb != -1) ? value.substring(0, lb) : value;

            if (lb != -1 && rb != -1) {
                String idxStr = value.substring(lb + 1, rb).trim();
                try { artifactNum = Integer.parseInt(idxStr); } catch (NumberFormatException ignored) {}
                if (artifactNum < 1) artifactNum = 1;
            }

            String repoPath = getGitHubRepoLocation(repoUrl);
            if (repoPath == null || repoPath.isEmpty()) {
                logger.info("Repository path not found for: " + value);
            try {
                Path out = decideInstallPath(entry.getKey());
                try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                    return true;
                }
            } catch (Throwable ignored) { /* fall through to legacy builder */ }
            return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);

            }

            String regex = queryParam(query, "get");
            String preParam = queryParam(query, "prerelease");
            boolean allowPre = preParam != null ? Boolean.parseBoolean(preParam) : common.UpdateOptions.allowPreReleaseDefault;
            String forceBuildParam = queryParam(query, "autobuild");
            boolean forceBuild = forceBuildParam != null && Boolean.parseBoolean(forceBuildParam);

            if (forceBuild) {
                try {
                    try {
                        Path out = decideInstallPath(entry.getKey());
                        try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                        if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                            return true;
                        }
                    } catch (Throwable ignored) { }

                    return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);
                } catch (IOException e) {
                    logger.info("Forced GitHub build failed for repo " + repoPath + ": " + e.getMessage());
                    return false;
                }
            }

            JsonNode releases = fetchGithubJson("https://api.github.com/repos" + repoPath + "/releases", key);

            if (releases == null || !releases.isArray() || releases.size() == 0) {
                JsonNode latest = fetchGithubJson("https://api.github.com/repos" + repoPath + "/releases/latest", key);
                if (latest != null && latest.isObject()) {
                    com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.node.ArrayNode(new com.fasterxml.jackson.databind.ObjectMapper().getNodeFactory());
                    arr.add(latest);
                    releases = arr;
                }
            }

            String downloadUrl = null;
            if (releases == null || releases.size() == 0) {
                try {
                    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect("https://github.com" + repoPath + "/releases")
                            .userAgent("AutoUpdatePlugins")
                            .timeout(Math.max(15000, common.UpdateOptions.readTimeoutMs))
                            .get();
                    for (org.jsoup.nodes.Element a : doc.select("a[href]")) {
                        String href = a.attr("abs:href");
                        if (href.contains("/releases/download/") && href.endsWith(".jar")) {
                            downloadUrl = href;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (downloadUrl == null && releases != null && releases.size() > 0) {
                int seen = 0;
                for (JsonNode rel : releases) {
                    if (!allowPre && rel.has("prerelease") && rel.get("prerelease").asBoolean()) continue;
                    JsonNode assets = rel.get("assets");
                    if (assets == null || !assets.isArray()) continue;

                    for (JsonNode asset : assets) {
                        String name = asset.has("name") ? asset.get("name").asText() : "";
                        if (!name.toLowerCase().endsWith(".jar")) continue;

                        if (regex != null && !regex.isEmpty()) {
                            if (name.matches(regex)) {
                                downloadUrl = asset.get("browser_download_url").asText();
                                break;
                            }
                        } else {
                            seen++;
                            if (seen == artifactNum) {
                                downloadUrl = asset.get("browser_download_url").asText();
                                break;
                            }
                        }
                    }
                    if (downloadUrl != null) break;
                }
            }

            if (downloadUrl == null || downloadUrl.isEmpty()) {
                if (common.UpdateOptions.debug) {
                    logger.info("[DEBUG] No GitHub .jar asset found for " + repoPath + " — attempting source build.");
                }
            try {
                Path out = decideInstallPath(entry.getKey());
                try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                    return true;
                }
            } catch (Throwable ignored) { /* fall through to legacy builder */ }
            return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);

            }

            try {
                boolean ok = pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key);
                if (!ok) {
                    if (common.UpdateOptions.debug) {
                        logger.info("[DEBUG] GitHub asset download failed, falling back to source build for " + repoPath);
                    }
            try {
                Path out = decideInstallPath(entry.getKey());
                try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                    return true;
                }
            } catch (Throwable ignored) { /* fall through to legacy builder */ }
            return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);

                }
                return true;
            } catch (Throwable t) {
                if (common.UpdateOptions.debug) {
                    logger.info("[DEBUG] GitHub asset download threw " + t.getClass().getSimpleName()
                            + " — falling back to source build for " + repoPath);
                }
                try {
                    Path out = decideInstallPath(entry.getKey());
                    try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                    if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                        return true;
                    }
                } catch (Throwable ignored) { /* fall through to legacy builder */ }

                return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);

            }
        } catch (Throwable t) {
            if (common.UpdateOptions.debug) {
                logger.info("[DEBUG] handleGitHubDownload failed for " + value + " : " + t.getMessage()
                        + " — building from source as fallback.");
            }
            try {
                Path out = decideInstallPath(entry.getKey());
                try { Files.createDirectories(out.getParent()); } catch (Exception ignored) {}
                if (GitHubBuild.handleGitHubBuild(logger, value, out, key)) {
                    return true;
                }
                String repoPath = getGitHubRepoLocation(value);
                return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key);
            } catch (Exception e) {
                logger.info("Failed to build plugin from GitHub repo (final fallback): " + e.getMessage());
                return false;
            }

        }
    }


    private boolean handleGitHubDevDownload(String key, Map.Entry<String, String> entry, String value) {
        String repoPath;
        int artifactNum = 1;
        String multiIdentifier = "[";
        String subString;
        String queryD = null;
        int qIdx = value.indexOf('?');
        if (qIdx != -1) {
            queryD = value.substring(qIdx + 1);
            value = value.substring(0, qIdx);
        }
        if (value.contains(multiIdentifier)) {
            int startIndex = value.indexOf(multiIdentifier);
            int endIndex = value.indexOf("]", startIndex);
            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
            subString = value.substring(0, value.indexOf(multiIdentifier));
        } else {
            int idx = value.indexOf("/dev");
            subString = idx > 0 ? value.substring(0, idx) : value;
        }

        repoPath = getGitHubRepoLocation(subString);

        String apiUrl = "https://api.github.com/repos" + repoPath + "/actions/artifacts";
        JsonNode node = fetchGithubJson(apiUrl, key);
        if (node == null) {
            logger.info("Failed to query GitHub actions artifacts for " + repoPath + "; check token/rate limit.");
            return false;
        }

        String getRegexD = queryParam(queryD, "get");
        String downloadUrl = null;
        int times = 0;
        for (JsonNode artifact : node.get("artifacts")) {
            if (artifact.has("name")) {
                if (getRegexD != null && artifact.get("name").asText().matches(getRegexD)) {
                    downloadUrl = artifact.get("archive_download_url").asText();
                    break;
                }
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

    private JsonNode fetchGithubJson(String apiUrl, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestProperty("User-Agent", "AutoUpdatePlugins");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(common.UpdateOptions.connectTimeoutMs);
            conn.setReadTimeout(common.UpdateOptions.readTimeoutMs);
            int code = conn.getResponseCode();
            if (code >= 400) {
                return null;
            }
            return new ObjectMapper().readTree(conn.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }

    private String getDefaultBranch(String repoPath, String token) throws IOException {
        String api = "https://api.github.com/repos" + repoPath;
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestProperty("User-Agent", "AutoUpdatePlugins");
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        JsonNode meta;
        try (InputStream in = conn.getInputStream()) {
            meta = new ObjectMapper().readTree(in);
        }
        return meta.has("default_branch") ? meta.get("default_branch").asText() : "main";
    }

    private long getLatestCommitDate(String repoPath, String branch, String token) throws IOException {
        String api = "https://api.github.com/repos" + repoPath + "/commits?sha=" + branch + "&per_page=1";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestProperty("User-Agent", "AutoUpdatePlugins");
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        JsonNode arr;
        try (InputStream in = conn.getInputStream()) {
            arr = new ObjectMapper().readTree(in);
        }
        if (arr.isArray() && arr.size() > 0) {
            JsonNode commit = arr.get(0).get("commit");
            String date = commit.get("committer").get("date").asText();
            return javax.xml.bind.DatatypeConverter.parseDateTime(date).getTimeInMillis();
        }
        return 0L;
    }

    private long getLatestReleaseDate(JsonNode releases) {
        if (releases == null || !releases.isArray() || releases.size() == 0) return 0L;
        JsonNode r = releases.get(0);
        String date = r.has("published_at") ? r.get("published_at").asText() : r.path("created_at").asText();
        try {
            return javax.xml.bind.DatatypeConverter.parseDateTime(date).getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long monthsBetween(long earlierMillis, long laterMillis) {
        if (earlierMillis <= 0 || laterMillis <= 0 || laterMillis < earlierMillis) return 0L;
        long days = (laterMillis - earlierMillis) / (1000L * 60L * 60L * 24L);
        return days / 30L;
    }

    private String queryParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String k = idx == -1 ? part : part.substring(0, idx);
            String v = idx == -1 ? "" : part.substring(idx + 1);
            if (k.equalsIgnoreCase(key)) return decode(v);
        }
        return null;
    }

    private String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignored) {
            return s;
        }
    }
}
