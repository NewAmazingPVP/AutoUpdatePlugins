package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;

public class PluginUpdater {

    private final PluginDownloader pluginDownloader;
    private final Logger logger;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile ExecutorService currentExecutor;

    private static final List<PendingMove> DEFERRED_MOVES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean DEFERRED_HOOK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicReference<Logger> DEFERRED_LOGGER = new AtomicReference<>();

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
            cancelRequested.set(false);
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
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Starting update run: entries=" + links.size() + ", parallel=" + Math.max(1, UpdateOptions.maxParallel));
            }
            int parallel = Math.max(1, UpdateOptions.maxParallel);
            ExecutorService ex = createExecutor(parallel);
            currentExecutor = ex;
            Semaphore sem = new Semaphore(parallel);
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, String> entry : links.entrySet()) {
                futures.add(ex.submit(() -> {
                    if (cancelRequested.get()) return;
                    boolean ok = false;
                    try {
                        sem.acquire();
                        if (cancelRequested.get()) return;
                        ok = handleUpdateEntry(platform, key, entry);
                    } catch (IOException e) {
                        ok = false;
                        logger.log(Level.WARNING, "Update failed for " + entry.getKey(), e);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        sem.release();
                    }
                    if (!ok) {
                        if (UpdateOptions.debug)
                            logger.info("[DEBUG] Download failed for " + entry.getKey() + " -> " + entry.getValue());
                        else logger.info("Download for " + entry.getKey() + " was not successful");
                    }
                }));
            }
            ex.shutdown();
            try {
                int cap = UpdateOptions.perDownloadTimeoutSec;
                if (cap > 0) {
                    ex.awaitTermination(cap, TimeUnit.SECONDS);
                } else {
                    ex.awaitTermination(7, TimeUnit.DAYS);
                }
            } catch (InterruptedException ignored) {
            }

        }).whenComplete((v, t) -> {
            updating.set(false);
            cancelRequested.set(false);
            currentExecutor = null;
        });
    }


    public void updatePlugin(String platform, String key, String name, String link) {
        CompletableFuture.runAsync(() -> {
            ExecutorService ex = createExecutor(1);
            try {
                ex.submit(() -> {
                    try {
                        handleUpdateEntry(platform, key, new AbstractMap.SimpleEntry<>(name, link));
                    } catch (IOException e) {
                        logger.warning("Download for " + name + " was not successful: " + e.getMessage());
                    }
                }).get();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error updating plugin " + name, e);
            } finally {
                ex.shutdownNow();
            }
        });
    }

    public boolean stopUpdates() {
        if (!updating.get()) {
            return false;
        }
        cancelRequested.set(true);
        ExecutorService ex = currentExecutor;
        if (ex != null) {
            try {
                ex.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        logger.info("Stop requested for ongoing update run. In-flight downloads may finish or abort shortly.");
        return true;
    }

    private static Path decideInstallPath(String pluginName) {
        return decideInstallPath(pluginName, null);
    }

    private static Path decideInstallPath(String pluginName, String customPath) {
        if (customPath != null && !customPath.trim().isEmpty()) {
            String cp = sanitizeCustomPath(customPath);
            if (cp != null && !cp.isEmpty()) {
                Path dir = Paths.get(cp);
                try {
                    Files.createDirectories(dir);
                } catch (Exception ignored) {
                }
                return dir.resolve(pluginName + ".jar");
            }
        }
        Path pluginsDir = Paths.get("plugins");
        Path mainJar = pluginsDir.resolve(pluginName + ".jar");

        if (UpdateOptions.useUpdateFolder) {
            Path updateDir = (UpdateOptions.updatePath != null && !UpdateOptions.updatePath.isEmpty())
                    ? Paths.get(UpdateOptions.updatePath)
                    : pluginsDir.resolve("update");
            try {
                Files.createDirectories(updateDir);
            } catch (Exception ignored) {
            }
            Path updateJar = updateDir.resolve(pluginName + ".jar");
            return Files.exists(mainJar) ? updateJar : mainJar;
        } else {
            return mainJar;
        }
    }

    private static String extractCustomPath(String raw) {
        if (raw == null) return null;
        int i = raw.indexOf('|');
        if (i < 0) return null;
        String tail = raw.substring(i + 1).trim();
        return tail.isEmpty() ? null : tail;
    }

    private static String stripLinkPart(String raw) {
        if (raw == null) return null;
        int i = raw.indexOf('|');
        return i < 0 ? raw.trim() : raw.substring(0, i).trim();
    }

    private static String sanitizeCustomPath(String cp) {
        if (cp == null) return null;
        cp = cp.trim();
        if (cp.isEmpty()) return null;
        cp = expandUserHome(cp);
        try {
            Path path = Paths.get(cp).normalize();
            String normalized = path.toString();
            return normalized.isEmpty() ? null : normalized;
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    private static String expandUserHome(String path) {
        if (path == null || !path.startsWith("~")) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return path;
        }
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }


    private ExecutorService createExecutor(int parallelism) {
        try {
            Class<?> execs = Class.forName("java.util.concurrent.Executors");
            Method m = execs.getMethod("newVirtualThreadPerTaskExecutor");
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
            String rawValue = entry.getValue();
            String customPath = null;
            String linkPart = rawValue;
            int pipe = rawValue != null ? rawValue.indexOf('|') : -1;
            if (pipe >= 0) {
                linkPart = rawValue.substring(0, pipe).trim();
                String tail = rawValue.substring(pipe + 1).trim();
                if (!tail.isEmpty()) customPath = tail;
            }
            String value = linkPart.replace("dev.bukkit.org/projects", "www.curseforge.com/minecraft/bukkit-plugins");

            if (value.contains("blob.build")) {
                return handleBlobBuild(value, key, entry);
            } else if (value.contains("thebusybiscuit.github.io/builds")) {
                return handleBusyBiscuitDownload(value, key, entry);
            } else if (value.contains("spigotmc.org")) {
                return handleSpigotDownload(key, entry, value);
            } else if (value.contains("github.com")) {
                return handleGitHubDownload(key, entry, value);
            } else if (value.contains("https://ci.")) {
                return handleJenkinsDownload(key, entry, value);
            } else if (value.contains("modrinth.com")) {
                return handleModrinthDownload(platform, key, entry, value);
            } else if (value.contains("https://hangar.papermc.io/")) {
                return handleHangarDownload(platform, key, entry, value);
            } else if (value.contains("builds.guizhanss.com")) {
                return handleGuizhanssDownload(value, key, entry);
            } else if (value.contains("minebbs.com")) {
                return handleMineBbsDownload(value, key, entry);
            } else if (value.contains("curseforge.com")) {
                return handleCurseForgeDownload(value, key, entry);
            } else {
                try {
                    if (pluginDownloader.downloadPlugin(value, entry.getKey(), key, customPath)) return true;
                } catch (IOException ignored) {
                }
                return handleGenericPageDownload(value, key, entry);
            }
        } catch (NullPointerException ignored) {
            return false;
        }
    }

    private Connection jsoup(String url) {
        Connection c = Jsoup.connect(url)
                .userAgent(PluginDownloader.getEffectiveUserAgent())
                .timeout(Math.max(15000, UpdateOptions.readTimeoutMs))
                .followRedirects(true);


        for (Map.Entry<String, String> e : PluginDownloader.getExtraHeaders().entrySet()) {
            c.header(e.getKey(), e.getValue());
        }


        if (!UpdateOptions.sslVerify) {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(X509Certificate[] c, String a) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                c.sslSocketFactory(sc.getSocketFactory());
            } catch (Throwable ignored) {
            }
        }

        return c;
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
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
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
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
        } catch (IOException e) {
            logger.info("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
            return false;
        }

    }

    private boolean handleHangarDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
        try {
            if (platform == null || platform.trim().isEmpty()) {
                platform = "paper";
            }
            if (platform.equalsIgnoreCase("spigot") || platform.equalsIgnoreCase("bukkit") || platform.equalsIgnoreCase("purpur")) {
                platform = "paper";
            }
            if (platform.toLowerCase(Locale.ROOT).contains("bungee")) {
                platform = "waterfall";
            }

            String query = null;
            int qIdx = value.indexOf('?');
            if (qIdx != -1) {
                query = value.substring(qIdx + 1);
                value = value.substring(0, qIdx);
            }

            String projectPath = extractHangarProjectPath(value);
            if (projectPath == null || projectPath.isEmpty()) {
                logger.info("Failed to determine Hangar project slug from URL: " + value);
                return false;
            }

            ReleasePreference preference = parseReleasePreference(query, UpdateOptions.allowPreReleaseDefault);
            String explicitChannel = queryParam(query, "channel");

            String latestVersion = resolveHangarVersion(projectPath, platform, preference, explicitChannel);
            if (latestVersion == null || latestVersion.isEmpty()) {
                logger.info("Failed to locate a suitable Hangar version for " + projectPath + ".");
                return false;
            }

            String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectPath + "/versions/" + latestVersion + "/" + platform.toUpperCase(Locale.ROOT) + "/download";
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
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


    private String extractHangarProjectPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            URL url = new URL(trimmed);
            String path = url.getPath();
            if (path != null && !path.isEmpty()) {
                String[] segments = path.split("/");
                List<String> parts = new ArrayList<>();
                for (String segment : segments) {
                    if (segment != null && !segment.isEmpty()) {
                        parts.add(segment);
                    }
                }
                if (!parts.isEmpty()) {
                    if (parts.size() >= 2) {
                        return parts.get(parts.size() - 2) + "/" + parts.get(parts.size() - 1);
                    }
                    return parts.get(parts.size() - 1);
                }
            }
        } catch (Exception ignored) {
            // treat as plain slug below
        }

        String cleaned = trimmed.replaceAll("^/+|/+$", "");
        if (cleaned.contains("/")) {
            String[] segs = cleaned.split("/");
            if (segs.length >= 2) {
                return segs[segs.length - 2] + "/" + segs[segs.length - 1];
            }
        }
        return cleaned;
    }

    private String resolveHangarVersion(String projectPath, String platform, ReleasePreference preference, String explicitChannel) throws IOException {
        if ((explicitChannel == null || explicitChannel.trim().isEmpty()) && preference.isReleaseOnly()) {
            return getHangarLatestVersion(projectPath);
        }

        String normalizedPlatform = platform == null ? "PAPER" : platform.toUpperCase(Locale.ROOT);
        ObjectMapper mapper = new ObjectMapper();
        int offset = 0;
        long bestScore = Long.MIN_VALUE;
        String bestVersion = null;

        while (offset < 250) {
            String api = "https://hangar.papermc.io/api/v1/projects/" + projectPath + "/versions?limit=25&offset=" + offset;
            JsonNode root = mapper.readTree(new URL(api));
            JsonNode results = root.path("result");
            if (!results.isArray() || results.size() == 0) {
                break;
            }

            for (JsonNode version : results) {
                if (version == null) continue;
                String versionName = version.path("name").asText("");
                if (versionName.isEmpty()) continue;

                JsonNode downloads = version.path("downloads");
                if (downloads.isMissingNode()) continue;
                JsonNode platformNode = downloads.path(normalizedPlatform);
                if (platformNode.isMissingNode()) continue;

                String channelName = version.path("channel").path("name").asText("");
                if (explicitChannel != null && !explicitChannel.trim().isEmpty()) {
                    if (channelName.equalsIgnoreCase(explicitChannel)) {
                        return versionName;
                    }
                    continue;
                }

                String typeKey = hangarTypeKey(channelName);
                if (!preference.allowsType(typeKey)) {
                    continue;
                }

                long when = parseInstantMillis(version.path("createdAt").asText(null));
                long score = computeTypeBoost(typeKey, preference) + when;
                if (score > bestScore) {
                    bestScore = score;
                    bestVersion = versionName;
                }
            }

            offset += results.size();
        }

        return bestVersion;
    }

    private String hangarTypeKey(String channelName) {
        if (channelName == null) return "other";
        String lower = channelName.toLowerCase(Locale.ROOT);
        if (lower.contains("release") || lower.contains("stable") || lower.contains("default")) {
            return "release";
        }
        if (lower.contains("alpha") || lower.contains("snapshot") || lower.contains("nightly") || lower.contains("dev") || lower.contains("bleeding")) {
            return "alpha";
        }
        if (lower.contains("beta") || lower.contains("rc") || lower.contains("pre") || lower.contains("preview")) {
            return "beta";
        }
        return "other";
    }

    private long parseInstantMillis(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long computeTypeBoost(String versionType, ReleasePreference preference) {
        if (preference == null) return 0L;
        if (versionType == null) versionType = "";
        String vt = versionType.toLowerCase(Locale.ROOT);
        if (preference.preferLatest) {
            return 0L;
        }
        switch (vt) {
            case "release":
            case "stable":
                long releaseBase = 1_000_000_000_000_000L;
                if (preference.preferPreRelease) releaseBase -= 100_000_000_000_000L;
                if (preference.preferAlpha) releaseBase -= 200_000_000_000_000L;
                return releaseBase;
            case "beta":
            case "rc":
            case "prerelease":
            case "pre-release":
            case "preview":
                if (!preference.allowPreRelease) return -1_000_000_000_000_000L;
                long preBase = 900_000_000_000_000L;
                if (preference.preferPreRelease) preBase += 150_000_000_000_000L;
                if (preference.preferAlpha) preBase -= 50_000_000_000_000L;
                return preBase;
            case "alpha":
            case "snapshot":
            case "nightly":
            case "dev":
            case "bleeding":
                if (!preference.allowAlpha) return -1_200_000_000_000_000L;
                long alphaBase = 880_000_000_000_000L;
                if (preference.preferAlpha) alphaBase += 200_000_000_000_000L;
                return alphaBase;
            default:
                if (!preference.allowAlpha) return -800_000_000_000_000L;
                long other = 700_000_000_000_000L;
                if (preference.preferAlpha) other += 100_000_000_000_000L;
                return other;
        }
    }

    private ReleasePreference parseReleasePreference(String query, boolean defaultAllowPreRelease) {
        boolean allowPreRelease = defaultAllowPreRelease;
        boolean allowAlpha = defaultAllowPreRelease;
        boolean preferPreRelease = false;
        boolean preferAlpha = false;
        boolean preferLatest = false;

        if (query != null) {
            String preParam = queryParam(query, "prerelease");
            if (preParam != null) {
                boolean val = parseBooleanFlag(preParam, true);
                allowPreRelease = val;
                if (val) preferPreRelease = true;
            }

            String preDash = queryParam(query, "pre-release");
            if (preDash != null) {
                boolean val = parseBooleanFlag(preDash, true);
                allowPreRelease = val;
                if (val) preferPreRelease = true;
            }

            String betaParam = queryParam(query, "beta");
            if (betaParam != null) {
                boolean val = parseBooleanFlag(betaParam, true);
                allowPreRelease = val || allowPreRelease;
                if (val) preferPreRelease = true;
            }

            String alphaParam = queryParam(query, "alpha");
            if (alphaParam != null) {
                boolean val = parseBooleanFlag(alphaParam, true);
                if (val) {
                    allowPreRelease = true;
                    allowAlpha = true;
                    preferAlpha = true;
                } else {
                    allowAlpha = false;
                    preferAlpha = false;
                }
            }

            String latestParam = queryParam(query, "latest");
            if (latestParam != null) {
                boolean val = parseBooleanFlag(latestParam, true);
                if (val) {
                    allowPreRelease = true;
                    allowAlpha = true;
                    preferLatest = true;
                } else {
                    preferLatest = false;
                }
            }

            String channelParam = queryParam(query, "channel");
            if (channelParam != null && !channelParam.trim().isEmpty()) {
                String ch = channelParam.trim().toLowerCase(Locale.ROOT);
                switch (ch) {
                    case "release":
                        allowPreRelease = false;
                        allowAlpha = false;
                        preferPreRelease = false;
                        preferAlpha = false;
                        preferLatest = false;
                        break;
                    case "beta":
                    case "prerelease":
                    case "pre-release":
                    case "preview":
                        allowPreRelease = true;
                        preferPreRelease = true;
                        break;
                    case "alpha":
                    case "snapshot":
                        allowPreRelease = true;
                        allowAlpha = true;
                        preferAlpha = true;
                        break;
                    case "latest":
                        allowPreRelease = true;
                        allowAlpha = true;
                        preferLatest = true;
                        break;
                }
            }
        }

        if (!allowPreRelease) {
            allowAlpha = false;
            preferPreRelease = false;
        }
        if (!allowAlpha) {
            preferAlpha = false;
        }

        return new ReleasePreference(allowPreRelease, allowAlpha, preferPreRelease, preferAlpha, preferLatest);
    }

    private boolean parseBooleanFlag(String raw, boolean defaultWhenBlank) {
        if (raw == null) {
            return defaultWhenBlank;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return defaultWhenBlank;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("1") || lower.equals("yes") || lower.equals("y") || lower.equals("on")) {
            return true;
        }
        if (lower.equals("false") || lower.equals("0") || lower.equals("no") || lower.equals("n") || lower.equals("off")) {
            return false;
        }
        return defaultWhenBlank;
    }

    private String firstNonNull(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private int parsePositiveInt(String input, int fallback) {
        if (input == null) return fallback;
        try {
            int parsed = Integer.parseInt(input.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static final class ReleasePreference {
        private final boolean allowPreRelease;
        private final boolean allowAlpha;
        private final boolean preferPreRelease;
        private final boolean preferAlpha;
        private final boolean preferLatest;

        private ReleasePreference(boolean allowPreRelease, boolean allowAlpha, boolean preferPreRelease, boolean preferAlpha, boolean preferLatest) {
            this.allowPreRelease = allowPreRelease;
            this.allowAlpha = allowAlpha;
            this.preferPreRelease = preferPreRelease;
            this.preferAlpha = preferAlpha;
            this.preferLatest = preferLatest;
        }

        private boolean allowsType(String type) {
            if (type == null) return allowAlpha;
            String t = type.toLowerCase(Locale.ROOT);
            if (t.equals("release") || t.equals("stable")) {
                return true;
            }
            if (t.equals("beta") || t.equals("prerelease") || t.equals("pre-release") || t.equals("preview") || t.equals("rc")) {
                return allowPreRelease;
            }
            if (t.equals("alpha") || t.equals("snapshot") || t.equals("nightly") || t.equals("dev") || t.equals("bleeding")) {
                return allowAlpha;
            }
            return allowAlpha;
        }

        private boolean isReleaseOnly() {
            return !allowPreRelease && !allowAlpha && !preferPreRelease && !preferAlpha && !preferLatest;
        }
    }


    private boolean handleModrinthDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
        try {
            String[] parts = value.split("/");
            String last = parts[parts.length - 1];
            int qi = last.indexOf('?');
            if (qi != -1) last = last.substring(0, qi);
            String projectSlug = last;


            String getRegex = null;
            String query = null;
            int qIndex = value.indexOf('?');
            if (qIndex != -1) {
                query = value.substring(qIndex + 1);
                getRegex = queryParam(query, "get");
            }

            ReleasePreference modrinthPreference = parseReleasePreference(query, UpdateOptions.allowPreReleaseDefault);

            boolean useRegex = getRegex != null && !getRegex.isEmpty();
            Pattern getPattern = null;
            if (useRegex) {
                try {
                    getPattern = Pattern.compile(getRegex);
                } catch (Throwable e) {
                    logger.info("Invalid get-regex for " + value + ": " + getRegex);
                    return false;
                }
            }

            JsonNode fallbackFile = null;
            ObjectMapper mapper = new ObjectMapper();
            int offset = 0;
            while (true) {
                String api = "https://api.modrinth.com/v2/project/" + projectSlug + "/version?offset=" + offset + "&limit=100";
                JsonNode versions = mapper.readTree(new URL(api));
                if (versions == null || !versions.isArray() || versions.size() == 0) break;

                for (JsonNode version : versions) {
                    JsonNode files = version.get("files");
                    if (files == null || !files.isArray()) continue;

                    boolean loaderOk = true;
                    if (version.has("loaders") && version.get("loaders").isArray() && platform != null && !platform.isEmpty()) {
                        loaderOk = false;
                        String p = platform.toLowerCase();
                        for (JsonNode l : version.get("loaders")) {
                            String lv = l.asText("").toLowerCase();
                            if (p.contains("paper")) {
                                if (lv.contains("paper")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("spigot")) {
                                if (lv.contains("spigot")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("bukkit")) {
                                if (lv.contains("bukkit")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("folia")) {
                                if (lv.contains("folia")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("velocity")) {
                                if (lv.contains("velocity")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("bungee")) {
                                if (lv.contains("bungeecord") || lv.contains("bungee")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else {
                                loaderOk = true;
                                break;
                            }
                        }
                    }
                    if (!loaderOk) continue;

                    if (useRegex) {
                        for (JsonNode f : files) {
                            String fname = f.has("filename") ? f.get("filename").asText("") : "";
                            try {
                                if (getPattern.matcher(fname).find()) {
                                    if (f.has("url")) {
                                        {
                                            String cp = extractCustomPath(entry.getValue());
                                            return pluginDownloader.downloadPlugin(f.get("url").asText(), entry.getKey(), key, cp);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    } else {
                        String versionType = version.path("version_type").asText("release");
                        if (!modrinthPreference.allowsType(versionType)) {
                            continue;
                        }
                        fallbackFile = pickBestFallback(fallbackFile, version, platform, modrinthPreference);
                    }
                }

                offset += versions.size();
            }
            if (useRegex) {
                logger.info("No Modrinth file matched get-regex for " + value + "; regex=" + getRegex);
                return false;
            }
            if (fallbackFile != null) {
                String cp = extractCustomPath(entry.getValue());
                return pluginDownloader.downloadPlugin(fallbackFile.get("url").asText(), entry.getKey(), key, cp);
            }
            logger.info("Failed to pick Modrinth file for " + value + ": no suitable files found.");
            return false;
        } catch (Exception e) {
            logger.info("Failed to download plugin from modrinth: " + e.getMessage());
            return false;
        }
    }

    private static JsonNode pickBestFallback(
            JsonNode currentBestFile,
            JsonNode version,
            String platform,
            ReleasePreference preference
    ) {
        JsonNode files = version.get("files");
        JsonNode chosen = null;
        for (JsonNode f : files) {
            if (f.path("primary").asBoolean(false)) { chosen = f; break; }
        }
        if (chosen == null) chosen = files.get(0);

        String vt = version.path("version_type").asText("release");
        long typeBoost = computeTypeBoost(vt, preference);

        long when = 0L;
        String published = version.path("date_published").asText(null);
        if (published != null) {
            try { when = java.time.Instant.parse(published).toEpochMilli(); } catch (Throwable ignored) {}
        }
        long baseScore = typeBoost + when;

        String p = platform == null ? "" : platform.toLowerCase();

        java.util.List<String> want = new java.util.ArrayList<>();
        if (p.contains("folia")) {
            want.add("folia");
            want.add("paper");
            want.add("purpur");
            want.add("spigot");
            want.add("bukkit");
        } else if (p.contains("paper") || p.contains("spigot") || p.contains("bukkit") || p.contains("purpur")) {
            want.add("paper");
            want.add("purpur");
            want.add("spigot");
            want.add("bukkit");
        } else if (p.contains("velocity")) {
            want.add("velocity");
        } else if (p.contains("bungee") || p.contains("bungeecord") || p.contains("waterfall")) {
            want.add("bungee");
            want.add("bungeecord");
            want.add("waterfall");
        }

        String[] avoidMods = new String[] { "fabric", "quilt", "forge", "neoforge", ".mrpack" };

        com.fasterxml.jackson.databind.JsonNode best = currentBestFile;
        long bestScore = currentBestFile != null
                ? currentBestFile.path("__score").asLong(Long.MIN_VALUE)
                : Long.MIN_VALUE;

        for (com.fasterxml.jackson.databind.JsonNode f : files) {
            if (!f.has("url")) continue;
            String fname = f.path("filename").asText("").toLowerCase();
            boolean primary = f.path("primary").asBoolean(false);

            long s = baseScore;
            if (primary) s += 5_000_000_000_000L;

            boolean matchedPlatform = false;
            for (String t : want) {
                if (!t.isEmpty() && fname.contains(t)) {
                    s += 200_000_000_000_000L;
                    matchedPlatform = true;
                    break;
                }
            }
            if (!matchedPlatform) {
                for (String t : avoidMods) {
                    if (fname.contains(t)) {
                        s -= 100_000_000_000_000L;
                        break;
                    }
                }
            }

            if (s > bestScore) {
                com.fasterxml.jackson.databind.node.ObjectNode annotated = f.deepCopy();
                annotated.put("__score", s);
                annotated.put("__published", published == null ? "" : published);
                annotated.put("__version_type", vt == null ? "" : vt);
                best = annotated;
                bestScore = s;
            }
        }
        return best;
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
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
        } catch (Exception e) {
            logger.info("Failed to download from guizhanss builds: " + e.getMessage());
            return false;
        }
    }

    private boolean handleMineBbsDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            if (value.contains("minebbs.com")) {
                String base = value.replaceAll("/+$", "");
                String dUrl = base + "/download";
                String cp = extractCustomPath(entry.getValue());
                return pluginDownloader.downloadPlugin(dUrl, entry.getKey(), key, cp);
            }

            Document doc = jsoup(value).get();
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (href.endsWith(".jar") || href.contains("/download") || href.contains("hangar.papermc.io") || href.contains("github.com")) {
                    if (href.endsWith(".jar")) {
                        String cp = extractCustomPath(entry.getValue());
                        return pluginDownloader.downloadPlugin(href, entry.getKey(), key, cp);
                    }
                    String cp = extractCustomPath(entry.getValue());
                    String forward = (cp != null && !cp.isEmpty()) ? (href + " | " + cp) : href;
                    return handleUpdateEntry("paper", key, new AbstractMap.SimpleEntry<>(entry.getKey(), forward));
                }
            }
            return false;
        } catch (Exception e) {
            logger.info("Failed to parse MineBBS page: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("ConstantConditions")
    private boolean handleCurseForgeDownload(String value, String key, Map.Entry<String, String> entry) {
        try {

            Function<String, String> extractSlug = (String url) -> {
                try {
                    Matcher m = Pattern
                            .compile("/minecraft/[^/]+/([^/?#]+)", Pattern.CASE_INSENSITIVE)
                            .matcher(new URL(url).getPath());
                    if (m.find()) return m.group(1);
                } catch (Throwable ignored) {
                }
                return null;
            };

            Function<String, String> httpGet = (String url) -> {
                HttpURLConnection conn = null;
                try {
                    int readTimeout = Math.max(15000, UpdateOptions.readTimeoutMs);
                    int connectTimeout = Math.max(10000, Math.min(readTimeout, 15000));

                    URL u = new URL(url);
                    conn = (HttpURLConnection) u.openConnection();

                    if (conn instanceof HttpsURLConnection && !UpdateOptions.sslVerify) {
                        try {
                            TrustManager[] trustAll = new TrustManager[]{
                                    new X509TrustManager() {
                                        public void checkClientTrusted(X509Certificate[] c, String a) {
                                        }

                                        public void checkServerTrusted(X509Certificate[] c, String a) {
                                        }

                                        public X509Certificate[] getAcceptedIssuers() {
                                            return new X509Certificate[0];
                                        }
                                    }
                            };
                            SSLContext sc = SSLContext.getInstance("TLS");
                            sc.init(null, trustAll, new SecureRandom());
                            ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                            ((HttpsURLConnection) conn).setHostnameVerifier((h, s) -> true);
                        } catch (Throwable ignored) {
                        }
                    }

                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(connectTimeout);
                    conn.setReadTimeout(readTimeout);

                    conn.setRequestProperty("User-Agent", PluginDownloader.getEffectiveUserAgent());
                    conn.setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8");
                    conn.setRequestProperty("Accept-Encoding", "gzip");
                    conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    Map<String, String> extra = PluginDownloader.getExtraHeaders();
                    if (extra != null) {
                        for (Map.Entry<String, String> e2 : extra.entrySet()) {
                            if (e2.getKey() != null && e2.getValue() != null) {
                                conn.setRequestProperty(e2.getKey(), e2.getValue());
                            }
                        }
                    }

                    int code = conn.getResponseCode();
                    InputStream raw = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    if (raw == null) {
                        logger.info("[CF] HTTP " + code + " – " + url + " (no body)");
                        return null;
                    }

                    String encoding = conn.getHeaderField("Content-Encoding");
                    InputStream in = raw;
                    try {
                        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                            in = new GZIPInputStream(raw);
                        } else {
                            in = new PushbackInputStream(raw, 2);
                            PushbackInputStream pb = (PushbackInputStream) in;
                            int b1 = pb.read();
                            int b2 = pb.read();
                            if (b1 == 0x1f && b2 == 0x8b) {
                                pb.unread(new byte[]{(byte) b1, (byte) b2});
                                in = new GZIPInputStream(pb);
                            } else {
                                pb.unread(new byte[]{(byte) b1, (byte) b2});
                            }
                        }
                    } catch (Throwable ignored) {
                        in = raw;
                    }

                    String ctype = conn.getHeaderField("Content-Type");
                    String charsetName = "UTF-8";
                    if (ctype != null) {
                        Matcher m = Pattern
                                .compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE)
                                .matcher(ctype);
                        if (m.find()) charsetName = m.group(1).trim();
                    }
                    Charset cs;
                    try {
                        cs = Charset.forName(charsetName);
                    } catch (Throwable t) {
                        cs = StandardCharsets.UTF_8;
                    }

                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, cs))) {
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[8192];
                        int r;
                        while ((r = br.read(buf)) != -1) sb.append(buf, 0, r);
                        if (code < 200 || code >= 300) {
                            logger.info("[CF] HTTP " + code + " body snippet: " + sb.substring(0, Math.min(sb.length(), 200)));
                            return null;
                        }
                        return sb.toString();
                    }
                } catch (Throwable t) {
                    logger.info("[CF] httpGet error for " + url + ": " + t.getMessage());
                    return null;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            };

            String slug = extractSlug.apply(value);
            if (slug == null || slug.isEmpty()) {
                logger.info("[CF] Could not extract slug from URL: " + value);
                return false;
            }

            String projApi = "https://api.curseforge.com/servermods/projects?search=" +
                    URLEncoder.encode(slug, StandardCharsets.UTF_8.name());
            String projJson = httpGet.apply(projApi);
            if (projJson == null || projJson.isEmpty()) {
                logger.info("[CF] servermods projects search returned nothing for slug=" + slug);
                return false;
            }

            ObjectMapper om = new ObjectMapper();
            JsonNode projArr = om.readTree(projJson);
            if (!projArr.isArray() || projArr.size() == 0) {
                logger.info("[CF] No projects found for slug=" + slug);
                return false;
            }

            String projectId = null;
            for (JsonNode p : projArr) {
                String s = p.has("slug") ? p.get("slug").asText() : null;
                if (slug.equalsIgnoreCase(s)) {
                    projectId = p.get("id").asText();
                    break;
                }
            }
            if (projectId == null) {
                projectId = projArr.get(0).get("id").asText();
            }
            logger.info("[CF] Resolved projectId=" + projectId + " via servermods projects search");

            String filesApi = "https://api.curseforge.com/servermods/files?projectIds=" + projectId;
            String filesJson = httpGet.apply(filesApi);
            if (filesJson == null || filesJson.isEmpty()) {
                logger.info("[CF] servermods files returned nothing for projectId=" + projectId);
                return false;
            }

            JsonNode filesArr = om.readTree(filesJson);
            if (!filesArr.isArray() || filesArr.size() == 0) {
                logger.info("[CF] No files for projectId=" + projectId);
                return false;
            }

            JsonNode latest = filesArr.get(filesArr.size() - 1);

            String downloadUrl = latest.has("downloadUrl") ? latest.get("downloadUrl").asText() : null;
            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                logger.info("[CF] Using LAST file downloadUrl from servermods: " + downloadUrl);
                String cp = extractCustomPath(entry.getValue());
                return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
            }

            String fileId = latest.has("id") ? latest.get("id").asText() : null;
            if (fileId != null && !fileId.isEmpty()) {
                String fallback = "https://www.curseforge.com/minecraft/bukkit-plugins/" + slug + "/download/" + fileId + "/file";
                logger.info("[CF] Constructed redirect URL for LAST file: " + fallback);
                String cp = extractCustomPath(entry.getValue());
                return pluginDownloader.downloadPlugin(fallback, entry.getKey(), key, cp);
            }

            logger.info("[CF] Could not determine a usable download URL from LAST file.");
            return false;

        } catch (Throwable e) {
            logger.info("Failed to parse CurseForge page: " + e.getMessage());
            return false;
        }
    }


    private boolean handleGenericPageDownload(String value, String key, Map.Entry<String, String> entry) {
        try {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(value).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
                conn.setReadTimeout(Math.max(15000, UpdateOptions.readTimeoutMs));
                conn.setRequestProperty("User-Agent", PluginDownloader.getEffectiveUserAgent());
                for (Map.Entry<String, String> h : PluginDownloader.getExtraHeaders().entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
                int code = conn.getResponseCode();
                String ct = conn.getContentType();
                String cd = conn.getHeaderField("Content-Disposition");
                boolean indicatesBinary = (ct != null && !ct.startsWith("text/") && !ct.contains("xml"))
                        || (cd != null && cd.toLowerCase().contains(".jar"));
                if (code >= 200 && code < 300 && indicatesBinary) {
                    String cp = extractCustomPath(entry.getValue());
                    return pluginDownloader.downloadPlugin(value, entry.getKey(), key, cp);
                }
            } catch (IOException ignored) {
            }

            Document doc = jsoup(value).get();
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (href.endsWith(".jar")) {
                    String cp = extractCustomPath(entry.getValue());
                    return pluginDownloader.downloadPlugin(href, entry.getKey(), key, cp);
                }
                if (href.contains("github.com") || href.contains("modrinth.com") || href.contains("spigotmc.org") || href.contains("hangar.papermc.io") || href.contains("builds.guizhanss.com")) {
                    String cp = extractCustomPath(entry.getValue());
                    String forward = (cp != null && !cp.isEmpty()) ? (href + " | " + cp) : href;
                    return handleUpdateEntry("paper", key, new AbstractMap.SimpleEntry<>(entry.getKey(), forward));
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
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(artifactUrl, entry.getKey(), key, cp);
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

        String repoPath = null;
        boolean forceBuild = false;
        try {
            String query = null;
            int qIdx = value.indexOf('?');
            if (qIdx != -1) {
                query = value.substring(qIdx + 1);
                value = value.substring(0, qIdx);
            }

            forceBuild = Boolean.parseBoolean(queryParam(query, "autobuild"));

            int artifactNum = 1;
            int lb = value.indexOf('['), rb = value.indexOf(']', lb + 1);
            String repoUrl = (lb != -1 && rb != -1) ? value.substring(0, lb) : value;

            if (lb != -1 && rb != -1) {
                String idxStr = value.substring(lb + 1, rb).trim();
                try {
                    artifactNum = Integer.parseInt(idxStr);
                } catch (NumberFormatException ignored) {
                }
                if (artifactNum < 1) artifactNum = 1;
            }

            repoPath = getGitHubRepoLocation(repoUrl);
            if (repoPath == null || repoPath.isEmpty()) {
                logger.info("Repository path not found for: " + value);
                return attemptSourceBuild(repoPath, entry, value, key, false, forceBuild);
            }

            String regex = queryParam(query, "get");
            ReleasePreference githubPreference = parseReleasePreference(query, UpdateOptions.allowPreReleaseDefault);
            boolean allowPre = githubPreference.allowPreRelease || githubPreference.allowAlpha;

            if (forceBuild) {
                return attemptSourceBuild(repoPath, entry, value, key, false, true);
            }

            JsonNode releases = fetchGithubJson("https://api.github.com/repos" + repoPath + "/releases", key);

            if (releases == null || !releases.isArray() || releases.size() == 0) {
                JsonNode latest = fetchGithubJson("https://api.github.com/repos" + repoPath + "/releases/latest", key);
                if (latest != null && latest.isObject()) {
                    ArrayNode arr = new ArrayNode(new ObjectMapper().getNodeFactory());
                    arr.add(latest);
                    releases = arr;
                }
            }

            String downloadUrl = null;
            if (releases == null || releases.size() == 0) {
                try {
                    Document doc = jsoup("https://github.com" + repoPath + "/releases").get();
                    for (Element a : doc.select("a[href]")) {
                        String href = a.attr("abs:href");
                        if (href.contains("/releases/download/") && href.endsWith(".jar")) {
                            downloadUrl = href;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
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
                if (UpdateOptions.debug) {
                    if (UpdateOptions.autoCompileEnable && UpdateOptions.autoCompileWhenNoJarAsset) {
                        logger.info("[DEBUG] No GitHub .jar asset found for " + repoPath + " — attempting source build.");
                    } else {
                        logger.info("[DEBUG] No GitHub .jar asset found for " + repoPath + " — source build disabled.");
                    }
                }
                return attemptSourceBuild(repoPath, entry, value, key, true, forceBuild);

            }

            try {
                String cp = extractCustomPath(entry.getValue());
                boolean ok = pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
                if (!ok) {
                    if (UpdateOptions.debug) {
                        if (UpdateOptions.autoCompileEnable) {
                            logger.info("[DEBUG] GitHub asset download failed, falling back to source build for " + repoPath);
                        } else {
                            logger.info("[DEBUG] GitHub asset download failed for " + repoPath + " — auto-compile disabled.");
                        }
                    }
                    return attemptSourceBuild(repoPath, entry, value, key, false, forceBuild);

                }
                return true;
            } catch (Throwable t) {
                if (UpdateOptions.debug) {
                    if (UpdateOptions.autoCompileEnable) {
                        logger.info("[DEBUG] GitHub asset download threw " + t.getClass().getSimpleName()
                                + " — falling back to source build for " + repoPath);
                    } else {
                        logger.info("[DEBUG] GitHub asset download threw " + t.getClass().getSimpleName()
                                + " for " + repoPath + " — auto-compile disabled.");
                    }
                }
                return attemptSourceBuild(repoPath, entry, value, key, false, forceBuild);

            }
        } catch (Throwable t) {
            if (UpdateOptions.debug) {
                if (UpdateOptions.autoCompileEnable) {
                    logger.info("[DEBUG] handleGitHubDownload failed for " + value + " : " + t.getMessage()
                            + " — building from source as fallback.");
                } else {
                    logger.info("[DEBUG] handleGitHubDownload failed for " + value + " : " + t.getMessage()
                            + " — auto-compile disabled.");
                }
            }
            return attemptSourceBuild(repoPath, entry, value, key, false, forceBuild);

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
            try {
                artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex).trim());
            } catch (NumberFormatException ignored) {
                artifactNum = 1;
            }
            subString = value.substring(0, value.indexOf(multiIdentifier));
        } else {
            int idx = value.indexOf("/dev");
            subString = idx > 0 ? value.substring(0, idx) : value;
        }

        repoPath = getGitHubRepoLocation(subString);
        if (repoPath == null || repoPath.isEmpty()) {
            logger.info("Repository path not found.");
            return false;
        }

        String apiUrl = "https://api.github.com/repos" + repoPath + "/actions/artifacts";
        JsonNode node = fetchGithubJson(apiUrl, key);
        if (node == null || node.get("artifacts") == null) {
            logger.info("Failed to query GitHub actions artifacts for " + repoPath + "; check token/rate limit.");
            return false;
        }

        String getRegexD = queryParam(queryD, "get");
        Pattern artifactPattern = null;
        if (getRegexD != null && !getRegexD.isEmpty()) {
            try {
                artifactPattern = Pattern.compile(getRegexD);
            } catch (PatternSyntaxException ex) {
                logger.info("Invalid get-regex for GitHub Actions artifact selection (" + getRegexD + ") : " + ex.getMessage());
                return false;
            }
        }

        String artifactOverride = firstNonNull(
                queryParam(queryD, "artifact"),
                queryParam(queryD, "index"),
                queryParam(queryD, "zip")
        );
        if (artifactOverride != null) {
            int parsed = parsePositiveInt(artifactOverride.trim(), -1);
            if (parsed > 0) {
                artifactNum = parsed;
            }
        }
        if (artifactNum < 1) artifactNum = 1;

        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode artifact : node.get("artifacts")) {
            if (artifact == null) continue;
            if (artifact.path("expired").asBoolean(false)) continue;
            String downloadUrl = artifact.path("archive_download_url").asText(null);
            if (downloadUrl == null || downloadUrl.isEmpty()) continue;
            JsonNode nameNode = artifact.get("name");
            String name = nameNode != null ? nameNode.asText("") : "";
            if (artifactPattern != null && (name == null || !artifactPattern.matcher(name).matches())) {
                continue;
            }
            candidates.add(artifact);
        }

        if (candidates.isEmpty()) {
            if (artifactPattern != null) {
                logger.info("No GitHub Actions artifacts matched the provided get-regex for " + repoPath + ".");
            } else {
                logger.info("No GitHub Actions artifacts available for " + repoPath + ".");
            }
            return false;
        }

        if (artifactNum > candidates.size()) {
            logger.info("Requested artifact number " + artifactNum + " exceeds available GitHub Actions artifacts (" + candidates.size() + ").");
            return false;
        }

        JsonNode selectedArtifact = candidates.get(artifactNum - 1);
        String downloadUrl = selectedArtifact.path("archive_download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            logger.info("Selected GitHub Actions artifact did not provide a download URL.");
            return false;
        }

        try {
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
        } catch (IOException e) {
            logger.info("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
            return false;
        }
    }

    private boolean handleSpigotDownload(String key, Map.Entry<String, String> entry, String value) {
        try {
            String pluginId = extractPluginIdFromLink(value);
            String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadPlugin(downloadUrl, entry.getKey(), key, cp);
        } catch (Exception e) {
            logger.info("Failed to download plugin from spigot, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
            return false;
        }
    }

    private boolean handleAlternateJenkinsDownload(String key, Map.Entry<String, String> entry, String value) {
        try {
            String downloadUrl = value + "lastSuccessfulBuild/artifact/*zip*/archive.zip";
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.downloadJenkinsPlugin(downloadUrl, entry.getKey(), cp);
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
            conn.setConnectTimeout(UpdateOptions.connectTimeoutMs);
            conn.setReadTimeout(UpdateOptions.readTimeoutMs);
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
            return DatatypeConverter.parseDateTime(date).getTimeInMillis();
        }
        return 0L;
    }

    private long getLatestReleaseDate(JsonNode releases) {
        if (releases == null || !releases.isArray() || releases.size() == 0) return 0L;
        JsonNode r = releases.get(0);
        String date = r.has("published_at") ? r.get("published_at").asText() : r.path("created_at").asText();
        try {
            return DatatypeConverter.parseDateTime(date).getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long monthsBetween(long earlierMillis, long laterMillis) {
        if (earlierMillis <= 0 || laterMillis <= 0 || laterMillis < earlierMillis) return 0L;
        long days = (laterMillis - earlierMillis) / (1000L * 60L * 60L * 24L);
        return days / 30L;
    }

    private boolean attemptSourceBuild(String repoPath, Map.Entry<String, String> entry, String url, String key,
                                       boolean noJarAsset, boolean forceBuild) {
        if (!forceBuild) {
            if (!UpdateOptions.autoCompileEnable) return false;
            if (noJarAsset && !UpdateOptions.autoCompileWhenNoJarAsset) return false;
        }
        try {
            String cp = extractCustomPath(entry.getValue());
            Path out = decideInstallPath(entry.getKey(), cp);
            try {
                Files.createDirectories(out.getParent());
            } catch (Exception ignored) {
            }
            if (GitHubBuild.handleGitHubBuild(logger, url, out, key)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (repoPath == null || repoPath.isEmpty()) return false;
        try {
            String cp = extractCustomPath(entry.getValue());
            return pluginDownloader.buildFromGitHubRepo(repoPath, entry.getKey(), key, cp);
        } catch (IOException e) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Source build failed for " + repoPath + ": " + e.getMessage());
            }
            return false;
        }
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
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignored) {
            return s;
        }
    }

    public static void moveStagedUpdatesIfNeeded(Logger logger, String platform, Path pluginsDir, String configuredUpdatePath) {
        if (!requiresManualUpdateMove(platform) || pluginsDir == null) {
            return;
        }

        Path normalizedPluginsDir = pluginsDir.toAbsolutePath().normalize();
        Set<Path> processed = new HashSet<>();
        List<Path> candidates = new ArrayList<>();

        if (configuredUpdatePath != null) {
            String trimmed = configuredUpdatePath.trim();
            if (!trimmed.isEmpty()) {
                try {
                    candidates.add(Paths.get(trimmed));
                } catch (InvalidPathException ex) {
                    if (logger != null) {
                        logger.log(Level.WARNING, "[AutoUpdatePlugins] Invalid custom update path '" + trimmed + "': " + ex.getMessage());
                    }
                }
            }
        }

        candidates.add(normalizedPluginsDir.resolve("update"));

        for (Path path : candidates) {
            if (path == null) continue;
            Path updateDir = path.toAbsolutePath().normalize();
            if (!processed.add(updateDir)) continue;
            if (!Files.isDirectory(updateDir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(updateDir)) {
                for (Path jar : stream) {
                    if (!Files.isRegularFile(jar)) continue;

                    String fileName = jar.getFileName() != null ? jar.getFileName().toString() : "";
                    if (fileName.isEmpty() || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;

                    Path target = normalizedPluginsDir.resolve(fileName);
                    Path jarParent = jar.getParent() != null ? jar.getParent().toAbsolutePath().normalize() : null;
                    if (jarParent != null && jarParent.equals(normalizedPluginsDir)) continue;

                    try {
                        Files.createDirectories(target.getParent());
                        Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
                        if (logger != null) {
                            logger.info("[AutoUpdatePlugins] Updated " + fileName + " from update folder.");
                        }
                    } catch (FileSystemException fse) {
                        if (isWindowsSharingViolation(fse)) {
                            if (logger != null) {
                                logger.info("[AutoUpdatePlugins] " + fileName + " is currently in use; deferring update until the proxy shuts down.");
                            }
                            scheduleDeferredMove(logger, jar, target);
                        } else {
                            if (logger != null) {
                                logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to move staged update " + jar + " -> " + target, fse);
                            }
                        }
                    } catch (IOException moveEx) {
                        if (logger != null) {
                            logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to move staged update " + jar + " -> " + target, moveEx);
                        }
                    }
                }
            } catch (IOException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to process update folder at " + updateDir, ex);
                }
            }
        }
    }

    private static void scheduleDeferredMove(Logger logger, Path source, Path target) {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        PendingMove pending = new PendingMove(normalizedSource, normalizedTarget);
        synchronized (DEFERRED_MOVES) {
            if (!DEFERRED_MOVES.contains(pending)) {
                DEFERRED_MOVES.add(pending);
            }
        }
        if (logger != null && DEFERRED_LOGGER.get() == null) {
            DEFERRED_LOGGER.compareAndSet(null, logger);
        }
        if (DEFERRED_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(PluginUpdater::runDeferredMoves, "AUP-DeferredMoves"));
        }
    }

    private static void runDeferredMoves() {
        List<PendingMove> snapshot;
        synchronized (DEFERRED_MOVES) {
            if (DEFERRED_MOVES.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(DEFERRED_MOVES);
            DEFERRED_MOVES.clear();
        }
        Logger logger = DEFERRED_LOGGER.get();
        if (isWindows()) {
            if (!runWindowsDeferredMoveScript(snapshot, logger)) {
                attemptInlineMoves(snapshot, logger);
            }
        } else {
            attemptInlineMoves(snapshot, logger);
        }
    }

    private static boolean runWindowsDeferredMoveScript(List<PendingMove> moves, Logger logger) {
        if (moves.isEmpty()) return true;
        Path baseDir = moves.get(0).source.getParent();
        if (baseDir == null) {
            baseDir = Paths.get(".");
        }
        baseDir = baseDir.toAbsolutePath().normalize();
        try {
            Path script = Files.createTempFile(baseDir, "aup-deferred-move-", ".cmd");
            Path logFile = baseDir.resolve("aup-deferred-move.log");
            List<String> lines = new ArrayList<>();
            lines.add("@echo off");
            lines.add("setlocal DISABLEDELAYEDEXPANSION");
            lines.add("set RETRY_LIMIT=120");
            lines.add("set WAIT_SECONDS=5");
            lines.add("set \"LOG=" + escapeForCmd(logFile.toString()) + "\"");
            lines.add("echo [%date% %time%] AutoUpdatePlugins deferred move script started.>>\"%LOG%\"");
            lines.add("");
            for (PendingMove move : moves) {
                String src = escapeForCmd(move.source.toString());
                String dst = escapeForCmd(move.target.toString());
                lines.add("call :move \"" + src + "\" \"" + dst + "\"");
                lines.add("if errorlevel 1 goto fail");
                lines.add("");
            }
            lines.add("goto done");
            lines.add("");
            lines.add(":move");
            lines.add("setlocal ENABLEDELAYEDEXPANSION");
            lines.add("set \"SRC=%~1\"");
            lines.add("set \"DST=%~2\"");
            lines.add("set COUNT=0");
            lines.add(":retry");
            lines.add("if not exist \"!SRC!\" (");
            lines.add("  endlocal & exit /b 0");
            lines.add(")");
            lines.add("for %%D in (\"!DST!\") do if not exist \"%%~dpD\" mkdir \"%%~dpD\" >nul 2>&1");
            lines.add("move /Y \"!SRC!\" \"!DST!\" >nul 2>&1");
            lines.add("if errorlevel 1 (");
            lines.add("  set /a COUNT+=1");
            lines.add("  if !COUNT! GEQ %RETRY_LIMIT% (");
            lines.add("    echo [%date% %time%] Failed to move \"!SRC!\" to \"!DST!\" after !COUNT! attempts.>>\"%LOG%\"");
            lines.add("    endlocal & exit /b 1");
            lines.add("  )");
            lines.add("  timeout /t %WAIT_SECONDS% >nul");
            lines.add("  goto retry");
            lines.add(")");
            lines.add("echo [%date% %time%] Moved \"!SRC!\" to \"!DST!\".>>\"%LOG%\"");
            lines.add("endlocal & exit /b 0");
            lines.add("");
            lines.add(":fail");
            lines.add("echo [%date% %time%] AutoUpdatePlugins deferred move script failed.>>\"%LOG%\"");
            lines.add("del \"%~f0\"");
            lines.add("exit /b 1");
            lines.add("");
            lines.add(":done");
            lines.add("echo [%date% %time%] AutoUpdatePlugins deferred move script completed.>>\"%LOG%\"");
            lines.add("del \"%~f0\"");
            lines.add("exit /b 0");

            Files.write(script, lines, StandardCharsets.UTF_8);
            new ProcessBuilder("cmd.exe", "/C", "call", script.toString()).start();
            if (logger != null) {
                logger.info("[AutoUpdatePlugins] Scheduled deferred plugin updates via " + script);
            }
            return true;
        } catch (IOException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to create deferred move script", ex);
            }
            return false;
        }
    }

    private static void attemptInlineMoves(List<PendingMove> moves, Logger logger) {
        for (PendingMove move : moves) {
            try {
                Files.createDirectories(move.target.getParent());
                Files.move(move.source, move.target, StandardCopyOption.REPLACE_EXISTING);
                if (logger != null) {
                    logger.info("[AutoUpdatePlugins] Deferred update applied for " + move.target.getFileName());
                }
            } catch (IOException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[AutoUpdatePlugins] Deferred move still failing " + move.source + " -> " + move.target, ex);
                }
            }
        }
    }

    private static boolean isWindowsSharingViolation(FileSystemException ex) {
        if (!isWindows()) return false;
        String reason = ex.getReason();
        if (reason != null) {
            String lower = reason.toLowerCase(Locale.ROOT);
            if (lower.contains("being used by another process") || lower.contains("sharing violation")) {
                return true;
            }
        }
        String message = ex.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            return lower.contains("being used by another process") || lower.contains("sharing violation");
        }
        return false;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String escapeForCmd(String path) {
        return path.replace("%", "%%");
    }

    private static final class PendingMove {
        private final Path source;
        private final Path target;

        private PendingMove(Path source, Path target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PendingMove)) return false;
            PendingMove that = (PendingMove) o;
            return Objects.equals(source, that.source) && Objects.equals(target, that.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, target);
        }
    }

    private static boolean requiresManualUpdateMove(String platform) {
        if (platform == null) return false;
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        return normalized.contains("velocity") || normalized.contains("waterfall") || normalized.contains("bungee");
    }
}
