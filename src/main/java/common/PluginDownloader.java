package common;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.InvalidPathException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class PluginDownloader {

    public interface InstallListener {
        void onInstall(String pluginName, Path targetPath);
    }

    private final Logger logger;
    private static Map<String, String> extraHeaders = new HashMap<>();
    private static String overrideUserAgent = null;
    private static volatile CloseableHttpClient pooledClient = null;
    private static volatile PoolingHttpClientConnectionManager connMgr = null;
    private static Boolean java11HttpAvailable = null;
    private volatile InstallListener installListener;

    public PluginDownloader(Logger logger) {
        this.logger = logger;
    }

    public void setInstallListener(InstallListener listener) {
        this.installListener = listener;
    }

    private void backoffDelay(int attempt, int code, String link) {
        int base = Math.max(0, UpdateOptions.backoffBaseMs);
        int max = Math.max(base, UpdateOptions.backoffMaxMs);
        int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new Random().nextInt(250);
        if (UpdateOptions.debug) {
            logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static void setHttpHeaders(Map<String, String> headers, String userAgent) {
        extraHeaders = headers != null ? new HashMap<>(headers) : new HashMap<>();
        overrideUserAgent = (userAgent != null && !userAgent.trim().isEmpty()) ? userAgent.trim() : null;
    }

    public static Map<String, String> getExtraHeaders() {
        return extraHeaders;
    }

    public static String getEffectiveUserAgent() {
        if (overrideUserAgent != null && !overrideUserAgent.isEmpty()) return overrideUserAgent;
        if (!UpdateOptions.userAgents.isEmpty()) return UpdateOptions.userAgents.get(0);
        return "AutoUpdatePlugins";
    }

    private void notifyInstalled(String pluginName, Path targetPath) {
        InstallListener listener = installListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onInstall(pluginName, targetPath);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureClient() {
        if (pooledClient != null) return;
        synchronized (PluginDownloader.class) {
            if (pooledClient != null) return;
            connMgr = new PoolingHttpClientConnectionManager();
            connMgr.setMaxTotal(Math.max(32, UpdateOptions.maxParallel * 4));
            connMgr.setDefaultMaxPerRoute(Math.max(8, UpdateOptions.maxPerHost * 2));

            RequestConfig rc = RequestConfig.custom()
                    .setConnectTimeout(UpdateOptions.connectTimeoutMs)
                    .setSocketTimeout(UpdateOptions.readTimeoutMs)
                    .setConnectionRequestTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs))
                    .build();

            HttpClientBuilder builder = HttpClients.custom()
                    .setDefaultRequestConfig(rc)
                    .setConnectionManager(connMgr)
                    .disableContentCompression();

            if (!UpdateOptions.sslVerify) {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, new TrustManager[]{new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {
                        }

                        public void checkServerTrusted(X509Certificate[] c, String a) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }}, new SecureRandom());
                    SSLConnectionSocketFactory sslsf =
                            new SSLConnectionSocketFactory(
                                    sc, NoopHostnameVerifier.INSTANCE);
                    builder.setSSLSocketFactory(sslsf);
                } catch (Exception ignored) {
                }
            }

            pooledClient = builder.build();

        }
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken) throws IOException {
        return downloadPlugin(link, fileName, githubToken, null);
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken, String customPath) throws IOException {
        String host = null;
        Semaphore hostSem = null;
        try {
            try {
                host = new URL(link).getHost();
            } catch (Throwable ignored) {
            }
            if (host != null) {
                hostSem = UpdateOptions.hostSemaphores.computeIfAbsent(host.toLowerCase(Locale.ROOT), h -> new Semaphore(Math.max(1, UpdateOptions.maxPerHost)));
                hostSem.acquireUninterruptibly();
            }
        } catch (Throwable ignored) {
        }
        boolean requiresAuth = link.toLowerCase().contains("actions")
                && link.toLowerCase().contains("github")
                && githubToken != null && !githubToken.isEmpty();

        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        String outputFilePath = resolveOutputPath(fileName, customPath);
        String outputTempPath = outputFilePath + ".temp";

        try {
            for (int attempt = 1; attempt <= Math.max(2, UpdateOptions.maxRetries); attempt++) {
                File rawTmp = new File(rawTempPath);
                File outTmp = new File(outputTempPath);
                cleanupQuietly(rawTmp);
                cleanupQuietly(outTmp);
                try {
                    boolean downloaded = false;
                    if (hasJava11HttpClient()) {
                        downloaded = downloadWithJava11(link, githubToken, requiresAuth, rawTmp, attempt);
                    } else {
                        downloaded = downloadWithApache(link, githubToken, requiresAuth, rawTmp, attempt);
                    }

                    if (!downloaded) {
                        downloaded = downloadWithUrlConnection(link, githubToken, requiresAuth, rawTmp, attempt);
                    }

                    if (downloaded) {
                        if (postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath, outputTempPath, fileName)) {
                            return true;
                        }
                    }
                } catch (IOException e) {
                    logger.warning("Failed to download or extract plugin: " + e.getMessage());
                } finally {
                    cleanupQuietly(new File(rawTempPath));
                    cleanupQuietly(new File(outputTempPath));
                }
            }
            return false;
        } finally {
            if (hostSem != null) hostSem.release();
        }
    }

    public boolean installLocalFile(Path source, String fileName, String customPath) throws IOException {
        if (source == null) {
            throw new IOException("Local file path is null");
        }
        if (!Files.isRegularFile(source)) {
            logger.info("Local file not found: " + source);
            return false;
        }
        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        String outputFilePath = resolveOutputPath(fileName, customPath);
        String outputTempPath = outputFilePath + ".temp";

        File rawTmp = new File(rawTempPath);
        File outTmp = new File(outputTempPath);
        cleanupQuietly(rawTmp);
        cleanupQuietly(outTmp);

        Files.copy(source, rawTmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            return postProcessDownloadedFile(rawTmp, outTmp, outputFilePath, rawTempPath, outputTempPath, fileName);
        } finally {
            cleanupQuietly(new File(rawTempPath));
            cleanupQuietly(new File(outputTempPath));
        }
    }

    private boolean downloadWithJava11(String link, String githubToken, boolean requiresAuth, File rawTmp, int attempt) throws IOException {
        try {
            Java11Response r = executeJava11Get(link, githubToken, requiresAuth);
            try {
                int code = r.statusCode;
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    backoffDelay(attempt, code, link);
                    return false;
                }
                if (!downloadWithVerificationStream(rawTmp, r.body, r.contentLength, r.headers)) return false;
                return verifyChecksumIfProvidedHeaders(rawTmp, r.headers);
            } finally {
                closeQuietly(r.body);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean downloadWithApache(String link, String githubToken, boolean requiresAuth, File rawTmp, int attempt) throws IOException {
        try {
            ensureClient();
            CloseableHttpResponse resp = executeApacheGet(link, githubToken, requiresAuth);
            try {
                int code = resp.getStatusLine() != null ? resp.getStatusLine().getStatusCode() : 0;
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    backoffDelay(attempt, code, link);
                    return false;
                }
                if (!downloadWithVerificationApache(rawTmp, resp)) return false;
                return verifyChecksumIfProvidedApache(rawTmp, resp);
            } finally {
                try {
                    resp.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean downloadWithUrlConnection(String link, String githubToken, boolean requiresAuth, File rawTmp, int attempt) throws IOException {
        HttpURLConnection connection = openConnection(link, githubToken, requiresAuth);
        int code = 0;
        try {
            code = connection.getResponseCode();
        } catch (IOException ignored) {
        }
        if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
            backoffDelay(attempt, code, link);
            return false;
        }
        if (!downloadWithVerification(rawTmp, connection)) {
            logger.warning("Download failed (attempt " + attempt + ") — retrying lenient mode (old-plugin behavior)");
            try {
                connection = openConnection(link, githubToken, requiresAuth);
                return downloadLenient(rawTmp, connection);
            } catch (IOException ex) {
                return false;
            }
        }
        if (!verifyChecksumIfProvided(rawTmp, connection)) {
            logger.warning("Checksum mismatch from server");
            return false;
        }
        return true;
    }

    private boolean postProcessDownloadedFile(File rawTmp, File outTmp, String outputFilePath, String rawTempPath, String outputTempPath, String pluginName) throws IOException {
        if (isZipFile(rawTempPath)) {
            boolean extracted = extractFirstJarFromZip(rawTempPath, outputTempPath);
            if (!extracted) {
                moveReplace(rawTmp, outTmp);
            }
        } else {
            moveReplace(rawTmp, outTmp);
        }

        if (!validateJar(outTmp)) {
            logger.warning("Downloaded file is not a valid JAR");
            return false;
        }

        File target = new File(outputFilePath);
        if (UpdateOptions.rollbackEnabled) {
            try {
                RollbackManager.prepareBackup(logger, pluginName, target.toPath());
            } catch (Exception ex) {
                if (UpdateOptions.debug) {
                    logger.log(java.util.logging.Level.FINE, "[DEBUG] Unable to snapshot rollback for " + pluginName, ex);
                }
            }
        }

        if (UpdateOptions.debug)
            logger.info("[DEBUG] Ready to install: temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
        if (UpdateOptions.ignoreDuplicates && target.exists() && target.length() == outTmp.length() && sameDigest(target, outTmp, "MD5")) {
            cleanupQuietly(outTmp);
            cleanupQuietly(rawTmp);
            return true;
        }
        moveReplace(outTmp, target);
        if (UpdateOptions.rollbackEnabled) {
            try {
                RollbackManager.markInstalled(pluginName, target.toPath());
            } catch (Exception ignored) {
            }
        }
        notifyInstalled(pluginName, target.toPath());
        cleanupQuietly(rawTmp);
        return true;
    }


    private String getString(String fileName) {
        String basePlugins = "plugins/";
        String configuredFilePath = UpdateOptions.filePath;
        String configuredUpdatePath = UpdateOptions.updatePath;

        if (configuredFilePath != null && !configuredFilePath.isEmpty()) {
            return ensureDir(configuredFilePath) + fileName + ".jar";
        }

        File mainJar = new File(basePlugins + fileName + ".jar");

        if (UpdateOptions.useUpdateFolder) {
            String updateDir = (configuredUpdatePath != null && !configuredUpdatePath.isEmpty())
                    ? ensureDir(configuredUpdatePath)
                    : ensureDir(basePlugins + "update/");

            if (mainJar.exists()) {
                return updateDir + fileName + ".jar";
            } else {
                return basePlugins + fileName + ".jar";
            }
        }

        return basePlugins + fileName + ".jar";
    }

    private String resolveOutputPath(String fileName, String customPath) {
        if (customPath != null && !customPath.trim().isEmpty()) {
            String cp = sanitizeCustomPath(customPath);
            if (cp != null && !cp.isEmpty()) {
                String dir = ensureDir(cp);
                return dir + fileName + ".jar";
            }
        }
        return getString(fileName);
    }

    private String sanitizeCustomPath(String cp) {
        if (cp == null) return null;
        cp = cp.trim();
        if (cp.isEmpty()) return null;
        cp = expandUserHome(cp);
        try {
            Path path = Paths.get(cp).normalize();
            String normalized = path.toString();
            return normalized.isEmpty() ? null : normalized;
        } catch (InvalidPathException ex) {
            if (UpdateOptions.debug) {
                logger.info("[DEBUG] Ignoring custom path '" + cp + "' due to invalid path: " + ex.getMessage());
            }
            return null;
        }
    }

    private String expandUserHome(String path) {
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

    private String ensureDir(String dir) {
        if (dir == null || dir.isEmpty()) return dir;
        File directory = new File(dir);
        directory.mkdirs();
        String path = directory.getPath();
        if (!path.endsWith(File.separator)) path = path + File.separator;
        return path;
    }

    private boolean downloadPluginToFile(String outputFilePath, HttpURLConnection connection) throws IOException {
        return downloadWithVerification(new File(outputFilePath), connection);
    }


    private boolean extractFirstJarFromZip(String zipFilePath, String outputFilePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipFilePath)) {
            List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
            Optional<? extends ZipEntry> zipEntry = entries.stream()
                    .filter(entry -> !entry.isDirectory() &&
                            !entry.getName().toLowerCase().contains("javadoc") &&
                            !entry.getName().toLowerCase().contains("sources") &&
                            !entry.getName().toLowerCase().contains("api/") &&
                            entry.getName().endsWith(".jar"))
                    .findFirst();

            if (!zipEntry.isPresent()) {
                return false;
            }

            try (InputStream in = new BufferedInputStream(zipFile.getInputStream(zipEntry.get()), 65536);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFilePath), 65536)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
            return true;
        }
    }

    public boolean downloadJenkinsPlugin(String link, String fileName) {
        return downloadJenkinsPlugin(link, fileName, null);
    }

    public boolean downloadJenkinsPlugin(String link, String fileName, String customPath) {
        String tempBase = UpdateOptions.tempPath != null && !UpdateOptions.tempPath.isEmpty() ? ensureDir(UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        String outputFilePath = resolveOutputPath(fileName, customPath);
        String outputTempPath = outputFilePath + ".temp";
        HttpURLConnection seedConnection;
        try {
            seedConnection = openConnection(link, null, false);
        } catch (IOException e) {
            logger.info("Failed to open connection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        for (int attempt = 1; attempt <= Math.max(2, UpdateOptions.maxRetries); attempt++) {
            File rawTmp = new File(rawTempPath);
            File outTmp = new File(outputTempPath);
            cleanupQuietly(rawTmp);
            cleanupQuietly(outTmp);
            try {
                HttpURLConnection connection = openConnection(link, null, false);
                int code = 0;
                try {
                    code = connection.getResponseCode();
                } catch (IOException ignored) {
                }
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    int base = Math.max(0, UpdateOptions.backoffBaseMs);
                    int max = Math.max(base, UpdateOptions.backoffMaxMs);
                    int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new Random().nextInt(250);
                    if (UpdateOptions.debug)
                        logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored2) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                if (!downloadWithVerification(rawTmp, connection)) {
                    logger.info("Download failed (attempt " + attempt + ")");
                    continue;
                }
                if (isZipFile(rawTempPath)) {
                    boolean extracted = extractFirstJarFromZip(rawTempPath, outputTempPath);
                    if (!extracted) {
                        moveReplace(rawTmp, outTmp);
                    }
                } else {
                    moveReplace(rawTmp, outTmp);
                }
                if (!validateJar(outTmp)) {
                    logger.info("Downloaded file is not a valid JAR (attempt " + attempt + ")");
                    continue;
                }
                if (!verifyChecksumIfProvided(outTmp, connection)) {
                    logger.info("Checksum mismatch from server (attempt " + attempt + ")");
                    continue;
                }
                File target = new File(outputFilePath);
                if (UpdateOptions.debug)
                    logger.info("[DEBUG] Ready to install: temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
                if (target.exists() && target.length() == outTmp.length() && sameDigest(target, outTmp, "MD5")) {
                    cleanupQuietly(outTmp);
                    cleanupQuietly(rawTmp);
                    return true;
                }
                moveReplace(outTmp, target);
                notifyInstalled(fileName, target.toPath());
                cleanupQuietly(rawTmp);
                return true;
            } catch (IOException e) {
                logger.info("Failed to download or extract plugin: " + e.getMessage());
            } finally {
                cleanupQuietly(new File(rawTempPath));
                cleanupQuietly(new File(outputTempPath));
            }
        }
        return false;
    }


    public static boolean isZipFile(String filePath) {
        Objects.requireNonNull(filePath, "filePath");
        Path path = Paths.get(filePath);

        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (ZipException ex) {
            return false;
        } catch (IOException ex) {
            throw new UncheckedIOException("I/O while probing ZIP", ex);
        }
    }

    private void moveReplace(File from, File to) throws IOException {
        try {
            Files.createDirectories(to.getParentFile().toPath());
        } catch (IOException ignored) {
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpURLConnection openConnection(String link, String githubToken, boolean requiresAuth) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        connection.setInstanceFollowRedirects(true);

        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }

        connection.setRequestProperty("User-Agent", ua);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "application/octet-stream, */*");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Connection", "keep-alive");

        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + githubToken);
        }
        try {
            URI uri = URI.create(link);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? (":" + uri.getPort()) : "");
            connection.setRequestProperty("Referer", origin);
        } catch (Throwable ignored) {
        }

        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }

        connection.setConnectTimeout(UpdateOptions.connectTimeoutMs);
        connection.setReadTimeout(UpdateOptions.readTimeoutMs);

        if (UpdateOptions.debug) {
            logger.info("[DEBUG] OpenConnection url=" + link + ", auth=" + requiresAuth + ", ua=" + ua);
        }
        return connection;
    }

    private static boolean hasJava11HttpClient() {
        if (java11HttpAvailable != null) return java11HttpAvailable.booleanValue();
        synchronized (PluginDownloader.class) {
            if (java11HttpAvailable != null) return java11HttpAvailable.booleanValue();
            try {
                Class.forName("java.net.http.HttpClient");
                java11HttpAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                java11HttpAvailable = Boolean.FALSE;
            }
            return java11HttpAvailable.booleanValue();
        }
    }

    private static class Java11Response {
        final InputStream body;
        final int statusCode;
        final long contentLength;
        final Map<String, String> headers;

        Java11Response(InputStream body, int statusCode, long contentLength, Map<String, String> headers) {
            this.body = body;
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.headers = headers;
        }
    }

    private Java11Response executeJava11Get(String link, String githubToken, boolean requiresAuth) throws Exception {
        Class<?> httpClientCls = Class.forName("java.net.http.HttpClient");
        Class<?> httpRequestCls = Class.forName("java.net.http.HttpRequest");
        Class<?> httpResponseCls = Class.forName("java.net.http.HttpResponse");
        Class<?> bodyHandlersCls = Class.forName("java.net.http.HttpResponse$BodyHandlers");
        Class<?> bodyHandlerIface = Class.forName("java.net.http.HttpResponse$BodyHandler");
        Class<?> redirectCls = Class.forName("java.net.http.HttpClient$Redirect");
        Class<?> headersCls = Class.forName("java.net.http.HttpHeaders");
        Class<?> durationCls = Class.forName("java.time.Duration");

        Object builder = httpClientCls.getMethod("newBuilder").invoke(null);
        Object redirectNormal = Enum.valueOf((Class<? extends Enum>) redirectCls.asSubclass(Enum.class), "NORMAL");
        builder.getClass().getMethod("followRedirects", redirectCls).invoke(builder, redirectNormal);
        Object connTimeout = durationCls.getMethod("ofMillis", long.class).invoke(null, (long) UpdateOptions.connectTimeoutMs);
        builder.getClass().getMethod("connectTimeout", durationCls).invoke(builder, connTimeout);
        Object client = builder.getClass().getMethod("build").invoke(builder);

        URI uri = URI.create(link);
        Object reqBuilder = httpRequestCls.getMethod("newBuilder", URI.class).invoke(null, uri);

        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }


        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "User-Agent", ua);
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept-Encoding", "identity");
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept", "application/octet-stream, */*");
        reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Accept-Language", "en-US,en;q=0.9");
        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, "Authorization", "Bearer " + githubToken);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    reqBuilder.getClass().getMethod("header", String.class, String.class).invoke(reqBuilder, e.getKey(), e.getValue());
                }
            }
        }

        Object readTimeout = durationCls.getMethod("ofMillis", long.class).invoke(null, (long) UpdateOptions.readTimeoutMs);
        reqBuilder.getClass().getMethod("timeout", durationCls).invoke(reqBuilder, readTimeout);
        Object request = reqBuilder.getClass().getMethod("GET").invoke(reqBuilder);
        request = reqBuilder.getClass().getMethod("build").invoke(reqBuilder);

        Object bodyHandler = bodyHandlersCls.getMethod("ofInputStream").invoke(null);
        Object response = client.getClass().getMethod("send", httpRequestCls, bodyHandlerIface).invoke(client, request, bodyHandler);

        int status = (Integer) response.getClass().getMethod("statusCode").invoke(response);
        Object headers = response.getClass().getMethod("headers").invoke(response);
        Map<String, List<String>> rawMap = (Map<String, List<String>>) headers.getClass().getMethod("map").invoke(headers);
        Map<String, String> flat = new HashMap<>();
        if (rawMap != null) {
            for (Map.Entry<String, List<String>> e : rawMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty())
                    flat.put(e.getKey(), e.getValue().get(0));
            }
        }
        long expected = -1L;
        try {
            String cl = flat.get("Content-Length");
            if (cl == null) cl = flat.get("content-length");
            if (cl != null) expected = Long.parseLong(cl.trim());
        } catch (Throwable ignored) {
        }

        InputStream body = (InputStream) response.getClass().getMethod("body").invoke(response);
        return new Java11Response(body, status, expected, flat);
    }

    private boolean downloadWithVerificationStream(File outFile, InputStream in, long expected, Map<String, String> headers) throws IOException {
        boolean canTrustLength = expected >= 0;
        long written = 0L;
        try (InputStream in0 = new BufferedInputStream(in, 65536); OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in0.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean verifyChecksumIfProvidedHeaders(File file, Map<String, String> headers) {
        try {
            if (headers == null || headers.isEmpty()) return true;
            Map<String, String> h = new HashMap<>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null)
                    h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            String sha256 = h.get("x-checksum-sha256");
            if (sha256 == null) sha256 = h.get("x-checksum-sha256");
            String sha1 = h.get("x-checksum-sha1");
            String md5 = h.get("x-checksum-md5");
            String etag = h.get("etag");
            if (etag != null) etag = stripQuotes(etag);

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);
            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped (headers): " + e.getMessage());
            return true;
        }
        return true;
    }

    private void closeQuietly(InputStream in) {
        if (in != null) try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private CloseableHttpResponse executeApacheGet(String link, String githubToken, boolean requiresAuth) throws IOException {
        ensureClient();
        HttpGet get = new HttpGet(link);
        String ua = (overrideUserAgent != null && !overrideUserAgent.trim().isEmpty()
                && !"AutoUpdatePlugins".equalsIgnoreCase(overrideUserAgent))
                ? overrideUserAgent.trim() : null;
        if (ua == null && UpdateOptions.userAgents != null && !UpdateOptions.userAgents.isEmpty()) {
            ua = UpdateOptions.userAgents.get(new Random().nextInt(UpdateOptions.userAgents.size()));
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
        }
        get.setHeader("User-Agent", ua);
        get.setHeader("Accept-Encoding", "identity");
        get.setHeader("Accept", "application/octet-stream, */*");
        get.setHeader("Accept-Language", "en-US,en;q=0.9");
        get.setHeader("Connection", "keep-alive");
        if (requiresAuth && githubToken != null && !githubToken.isEmpty()) {
            get.setHeader("Authorization", "Bearer " + githubToken);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    get.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        return pooledClient.execute(get);
    }


    private static boolean isGithubishHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith("github.com")
                || h.endsWith("githubusercontent.com")
                || h.endsWith("codeload.github.com")
                || h.endsWith("objects.githubusercontent.com");
    }

    private boolean downloadWithVerification(File outFile, HttpURLConnection connection) throws IOException {
        long expected = -1L;
        boolean canTrustLength = true;

        try {
            expected = connection.getContentLengthLong();
            String ce = connection.getHeaderField("Content-Encoding");
            String te = connection.getHeaderField("Transfer-Encoding");
            if (expected < 0 || (ce != null && !"identity".equalsIgnoreCase(ce)) || ("chunked".equalsIgnoreCase(te))) {
                canTrustLength = false;
            }
        } catch (Throwable ignored) {
        }

        long written = 0L;
        try (InputStream in = new BufferedInputStream(connection.getInputStream(), 65536); OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            if (UpdateOptions.debug) {
                try {
                    logger.info("[DEBUG] HTTP code=" + connection.getResponseCode()
                            + ", type=" + connection.getContentType()
                            + ", length=" + expected
                            + (connection.getHeaderField("Content-Encoding") != null
                            ? ", enc=" + connection.getHeaderField("Content-Encoding") : ""));
                } catch (IOException ignored) {
                }
            }
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            logger.warning("Content-Length mismatch: expected=" + expected + ", got=" + written);
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean downloadWithVerificationApache(File outFile, CloseableHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) return false;
        long expected = entity.getContentLength();
        boolean canTrustLength = expected >= 0;

        long written = 0L;
        try (InputStream in = new BufferedInputStream(entity.getContent(), 65536);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private boolean verifyChecksumIfProvidedApache(File file, CloseableHttpResponse response) {
        try {
            Header h;
            String sha256 = (h = response.getFirstHeader("X-Checksum-SHA256")) != null ? h.getValue() : null;
            if (sha256 == null && (h = response.getFirstHeader("X-Checksum-Sha256")) != null) sha256 = h.getValue();
            String sha1 = (h = response.getFirstHeader("X-Checksum-SHA1")) != null ? h.getValue() : null;
            if (sha1 == null && (h = response.getFirstHeader("X-Checksum-Sha1")) != null) sha1 = h.getValue();
            String md5 = (h = response.getFirstHeader("X-Checksum-MD5")) != null ? h.getValue() : null;
            String etag = (h = response.getFirstHeader("ETag")) != null ? stripQuotes(h.getValue()) : null;

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);

            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped (apache): " + e.getMessage());
            return true;
        }
        return true;
    }


    private boolean downloadLenient(File outFile, HttpURLConnection connection) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(connection.getInputStream(), 65536);
            out = new BufferedOutputStream(new FileOutputStream(outFile), 65536);
            byte[] buffer = new byte[65536];
            int r;
            while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }


    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key) throws IOException {
        return buildFromGitHubRepo(repoPath, fileName, key, null);
    }

    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key, String customPath) throws IOException {
        if (repoPath == null || repoPath.isEmpty()) throw new IOException("Invalid repo path");
        if (UpdateOptions.debug) logger.info("[DEBUG] Starting GitHub build for " + repoPath);

        String defaultBranch = "main";
        try {
            HttpURLConnection info = openConnection("https://api.github.com/repos" + repoPath, key, true);
            info.setRequestProperty("Accept", "application/vnd.github+json");
            if (info.getResponseCode() == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                InputStream in = info.getInputStream();
                try {
                    while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
                String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                int i = json.indexOf("\"default_branch\"");
                if (i >= 0) {
                    int c = json.indexOf(':', i);
                    if (c > 0) {
                        int q1 = json.indexOf('"', c + 1);
                        int q2 = (q1 > 0) ? json.indexOf('"', q1 + 1) : -1;
                        if (q1 > 0 && q2 > q1) {
                            defaultBranch = json.substring(q1 + 1, q2);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (defaultBranch == null || defaultBranch.trim().isEmpty()) defaultBranch = "main";

        String[] branches = new String[]{defaultBranch, "main", "master"};
        File workDir = new File("plugins/build/" + fileName + "-" + System.currentTimeMillis());
        if (!workDir.mkdirs()) throw new IOException("Unable to create build dir: " + workDir);

        File zipFile = new File(workDir, "repo.zip");
        boolean gotZip = false;
        for (String br : branches) {
            String zipUrl = "https://codeload.github.com" + repoPath + "/zip/refs/heads/" + br;
            try {
                HttpURLConnection c = openConnection(zipUrl, key, true);
                c.setRequestProperty("Accept", "application/zip");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (downloadLenient(zipFile, c)) {
                    gotZip = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!gotZip) {
            logger.warning("Could not download repo zip for " + repoPath);
            throw new IOException("Could not download repo zip for " + repoPath);
        }
        if (UpdateOptions.debug) logger.info("[DEBUG] Downloaded repo zip to " + zipFile.getAbsolutePath());

        File repoRoot = new File(workDir, "repo");
        if (!repoRoot.exists() && !repoRoot.mkdirs()) {
            throw new IOException("Unable to create repo root");
        }
        unzipTo(zipFile, repoRoot);

        File buildRoot = findBuildRoot(repoRoot);
        if (buildRoot == null) {
            logger.warning("No Maven/Gradle build file found in " + repoRoot.getAbsolutePath());
            throw new IOException("No Maven/Gradle build file found in " + repoRoot.getAbsolutePath());
        }
        if (UpdateOptions.debug) logger.info("[DEBUG] Build root: " + buildRoot.getAbsolutePath());

        boolean isMaven = new File(buildRoot, "pom.xml").exists();
        boolean isGradle = !isMaven && (new File(buildRoot, "build.gradle").exists() || new File(buildRoot, "build.gradle.kts").exists());
        if (!isMaven && !isGradle) {
            logger.warning("Unknown build system at " + buildRoot);
            throw new IOException("Unknown build system at " + buildRoot);
        }

        int exit;
        if (isMaven) {
            File mvnw = findFile(buildRoot, "mvnw", "mvnw.cmd", "mvnw.bat");
            if (mvnw != null) setExecutable(mvnw);
            String cmd = (mvnw != null) ? mvnw.getAbsolutePath() : "mvn";
            exit = run(buildRoot, cmd, "-q", "-U", "-DskipTests", "package");
        } else {
            File grw = findFile(buildRoot, "gradlew", "gradlew.bat");
            if (grw != null) setExecutable(grw);
            String cmd = (grw != null) ? grw.getAbsolutePath() : "gradle";

            boolean hasShadow = fileContains(new File(buildRoot, "build.gradle"))
                    || fileContains(new File(buildRoot, "build.gradle.kts"));
            String task = hasShadow ? "shadowJar" : "build";
            exit = run(buildRoot, cmd, "--no-daemon", "-x", "test", task);
            if (exit != 0 && hasShadow) {
                exit = run(buildRoot, cmd, "--no-daemon", "-x", "test", "build");
            }
        }
        if (exit != 0) {
            logger.warning("Build failed with exit code " + exit);
            throw new IOException("Build failed with exit code " + exit);
        }


        File jar = pickBuiltJar(buildRoot);
        if (jar == null) {
            logger.warning("Could not locate built jar in " + buildRoot);
            throw new IOException("Could not locate built jar in " + buildRoot);
        }


        String outputFilePath = resolveOutputPath(fileName, customPath);
        File out = new File(outputFilePath);
        if (UpdateOptions.debug) logger.info("[DEBUG] Built jar selected: " + jar.getAbsolutePath());
        copyFile(jar, out);
        notifyInstalled(fileName, out.toPath());
        return true;
    }


    private Optional<Path> findSingleTopDir(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            Path only = null;
            int count = 0;
            for (Path p : ds) {
                count++;
                only = p;
                if (count > 1) return Optional.empty();
            }
            if (only != null && Files.isDirectory(only)) return Optional.of(only);
            return Optional.empty();
        }
    }

    private boolean hasFile(Path dir, String name) {
        return Files.exists(dir.resolve(name));
    }

    private boolean runGradle(Path projectRoot, boolean useWrapper) throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb;
        if (useWrapper) {
            File script = projectRoot.resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
            if (!isWindows) script.setExecutable(true);
            pb = new ProcessBuilder(script.getAbsolutePath(), "-q", "build", "-x", "test");
        } else {
            pb = new ProcessBuilder(isWindows ? "gradle.bat" : "gradle", "-q", "build", "-x", "test");
        }
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
            }
        }
        int code = p.waitFor();
        if (code != 0) logger.info("Gradle build exited with code " + code);
        return code == 0;
    }

    private boolean runMaven(Path projectRoot, boolean useWrapper) throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb;
        if (useWrapper) {
            File script = projectRoot.resolve(isWindows ? "mvnw.cmd" : "mvnw").toFile();
            if (!isWindows) script.setExecutable(true);
            pb = new ProcessBuilder(script.getAbsolutePath(), "-q", "-DskipTests", "package");
        } else {
            pb = new ProcessBuilder(isWindows ? "mvn.cmd" : "mvn", "-q", "-DskipTests", "package");
        }
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
            }
        }
        int code = p.waitFor();
        if (code != 0) logger.info("Maven build exited with code " + code);
        return code == 0;
    }

    private Path selectBuiltPluginJar(Path root) throws IOException {
        List<Path> jars;
        try (Stream<Path> s = Files.walk(root)) {
            jars = s.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).contains("sources"))
                    .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).contains("javadoc"))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (jars.isEmpty()) return null;

        for (Path p : jars) {
            if (looksLikePluginJar(p.toFile())) return p;
        }
        return jars.stream().max(Comparator.comparingLong(p -> p.toFile().length())).orElse(null);
    }

    private boolean looksLikePluginJar(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            return jf.getEntry("plugin.yml") != null || jf.getEntry("bungee.yml") != null || jf.getEntry("velocity-plugin.json") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void cleanupTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void cleanupQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private boolean validateJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) return false;
        try (JarFile jf = new JarFile(jarFile)) {
            return jf.size() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean verifyChecksumIfProvided(File file, HttpURLConnection connection) {
        try {
            String sha256 = coalesceHeader(connection, "X-Checksum-SHA256", "X-Checksum-Sha256");
            String sha1 = coalesceHeader(connection, "X-Checksum-SHA1", "X-Checksum-Sha1");
            String md5 = coalesceHeader(connection, "X-Checksum-MD5");
            String etag = stripQuotes(connection.getHeaderField("ETag"));

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1)) return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5)) return digestMatches(file, "MD5", md5);

            if (notBlank(etag) && isHex(etag)) {
                int len = etag.length();
                if (len == 32) return digestMatches(file, "MD5", etag);
                if (len == 40) return digestMatches(file, "SHA-1", etag);
                if (len == 64) return digestMatches(file, "SHA-256", etag);
            }
        } catch (Exception e) {
            logger.fine("Checksum verification skipped: " + e.getMessage());
            return true;
        }
        return true;
    }

    private boolean sameDigest(File a, File b, String algorithm) {
        try {
            String da = computeDigestHex(a, algorithm);
            String db = computeDigestHex(b, algorithm);
            return da.equalsIgnoreCase(db);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean digestMatches(File file, String algorithm, String expectedHex) throws Exception {
        String actual = computeDigestHex(file, algorithm);
        boolean ok = actual.equalsIgnoreCase(expectedHex.trim());
        if (!ok) {
            logger.warning("Checksum mismatch for " + file.getName() + ": expected=" + expectedHex + ", actual=" + actual);
        }
        return ok;
    }

    private String computeDigestHex(File file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536); DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buf = new byte[65536];
            while (dis.read(buf) != -1) {
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private String coalesceHeader(HttpURLConnection conn, String... names) {
        for (String n : names) {
            String v = conn.getHeaderField(n);
            if (notBlank(v)) return stripQuotes(v);
        }
        return null;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String stripQuotes(String s) {
        return s == null ? null : s.replace("\"", "").trim();
    }

    private boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }


    private void unzipTo(File zip, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 65536))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.toPath().resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir.toPath())) {
                    throw new IOException("Zip slip detected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outPath.toFile()))) {
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private File findBuildRoot(File root) {
        ArrayDeque<File> q = new ArrayDeque<File>();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) if (f.isDirectory()) q.add(f);
        }
        int depth = 0;
        while (!q.isEmpty() && depth <= 3) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                File d = q.poll();
                if (new File(d, "pom.xml").exists()) return d;
                if (new File(d, "build.gradle").exists() || new File(d, "build.gradle.kts").exists()) return d;
                File[] subs = d.listFiles(new FileFilter() {
                    public boolean accept(File f) {
                        return f.isDirectory();
                    }
                });
                if (subs != null) Collections.addAll(q, subs);
            }
            depth++;
        }
        return null;
    }

    private File findFile(File dir, String... names) {
        for (String n : names) {
            File f = new File(dir, n);
            if (f.exists()) return f;
        }
        return null;
    }

    private void setExecutable(File f) {
        try {
            f.setExecutable(true);
        } catch (Throwable ignored) {
        }
    }

    private boolean fileContains(File f) {
        if (!f.exists()) return false;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.indexOf("shadowJar") >= 0 || s.indexOf("com.github.johnrengelman.shadow") >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private int run(File cwd, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (UpdateOptions.debug) logger.info("[DEBUG] " + line);
                }
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            return p.waitFor();
        } catch (Exception e) {
            if (UpdateOptions.debug) logger.info("[DEBUG] Build failed to start: " + e.getMessage());
            return -1;
        }
    }

    private File pickBuiltJar(File buildRoot) {

        List<File> candidates = new ArrayList<File>();
        File libs = new File(buildRoot, "build/libs");
        if (libs.isDirectory()) {
            File[] arr = libs.listFiles(new FilenameFilter() {
                public boolean accept(File d, String n) {
                    return n.endsWith(".jar");
                }
            });
            if (arr != null) Collections.addAll(candidates, arr);
        }
        File target = new File(buildRoot, "target");
        if (target.isDirectory()) {
            File[] arr = target.listFiles(new FilenameFilter() {
                public boolean accept(File d, String n) {
                    return n.endsWith(".jar");
                }
            });
            if (arr != null) Collections.addAll(candidates, arr);
        }


        if (candidates.isEmpty()) {
            ArrayDeque<File> q = new ArrayDeque<File>();
            q.add(buildRoot);
            int depth = 0;
            while (!q.isEmpty() && depth <= 3) {
                int sz = q.size();
                for (int i = 0; i < sz; i++) {
                    File d = q.poll();
                    File lib = new File(d, "build/libs");
                    if (lib.isDirectory()) {
                        File[] arr = lib.listFiles(new FilenameFilter() {
                            public boolean accept(File dd, String n) {
                                return n.endsWith(".jar");
                            }
                        });
                        if (arr != null) Collections.addAll(candidates, arr);
                    }
                    File tgt = new File(d, "target");
                    if (tgt.isDirectory()) {
                        File[] arr = tgt.listFiles(new FilenameFilter() {
                            public boolean accept(File dd, String n) {
                                return n.endsWith(".jar");
                            }
                        });
                        if (arr != null) Collections.addAll(candidates, arr);
                    }
                    File[] subs = d.listFiles(new FileFilter() {
                        public boolean accept(File f) {
                            return f.isDirectory();
                        }
                    });
                    if (subs != null) Collections.addAll(q, subs);
                }
                depth++;
            }
        }

        if (candidates.isEmpty()) return null;


        List<File> filtered = new ArrayList<File>(candidates.size());
        for (File f : candidates) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.indexOf("-sources") >= 0) continue;
            if (n.indexOf("-javadoc") >= 0) continue;
            if (n.indexOf("original-") >= 0) continue;
            if (n.indexOf("tests") >= 0) continue;
            if (n.indexOf("test-fixtures") >= 0) continue;
            filtered.add(f);
        }
        if (filtered.isEmpty()) filtered = candidates;


        Collections.sort(filtered, new Comparator<File>() {
            public int compare(File a, File b) {
                int sa = scoreJar(a.getName().toLowerCase(Locale.ROOT));
                int sb = scoreJar(b.getName().toLowerCase(Locale.ROOT));
                return (sb - sa);
            }
        });

        return filtered.get(0);
    }

    private int scoreJar(String n) {
        int s = 0;
        if (n.indexOf("shadow") >= 0 || n.indexOf("-all") >= 0 || n.indexOf("shaded") >= 0) s += 10;
        if (n.indexOf("plugin") >= 0) s += 3;
        if (n.endsWith(".jar")) s += 1;
        return s;
    }

    private void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

}
