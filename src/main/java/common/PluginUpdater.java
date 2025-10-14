package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class PluginUpdater {

    private final PluginDownloader pluginDownloader;
    private final Logger logger;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile ExecutorService currentExecutor;

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
            String[] parts = value.split("/");
            String projectName = parts[parts.length - 1];
            String latestVersion = getHangarLatestVersion(projectName);
            String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/versions/" + latestVersion + "/" + platform.toUpperCase() + "/download";
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


    private boolean handleModrinthDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
        try {
            String[] parts = value.split("/");
            String last = parts[parts.length - 1];
            int qi = last.indexOf('?');
            if (qi != -1) last = last.substring(0, qi);
            String projectSlug = last;


            String getRegex = null;
            int qIndex = value.indexOf('?');
            if (qIndex != -1) getRegex = queryParam(value.substring(qIndex + 1), "get");

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
                                if (lv.contains("paper") || lv.contains("spigot") || lv.contains("bukkit")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("spigot") || p.contains("bukkit")) {
                                if (lv.contains("spigot") || lv.contains("bukkit")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("folia")) {
                                if (lv.contains("folia")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("velocity")) {
                                if (lv.contains("velocity") || lv.contains("paper") || lv.contains("spigot") || lv.contains("bukkit")) {
                                    loaderOk = true;
                                    break;
                                }
                            } else if (p.contains("bungee")) {
                                if (lv.contains("bungeecord") || lv.contains("bungee") || lv.contains("velocity")) {
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
                        if (fallbackFile == null && files.size() > 0 && files.get(0).has("url")) {
                            fallbackFile = files.get(0);
                        }
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
            String preParam = queryParam(query, "prerelease");
            boolean allowPre = preParam != null ? Boolean.parseBoolean(preParam) : UpdateOptions.allowPreReleaseDefault;

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
}
