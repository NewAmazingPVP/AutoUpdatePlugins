package common;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.jar.JarFile;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.DirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;

public class PluginDownloader {

    private final Logger logger;
    private static java.util.Map<String, String> extraHeaders = new java.util.HashMap<>();
    private static String overrideUserAgent = null;

    public PluginDownloader(Logger logger) {
        this.logger = logger;
    }

    public static void setHttpHeaders(java.util.Map<String, String> headers, String userAgent) {
        extraHeaders = headers != null ? new java.util.HashMap<>(headers) : new java.util.HashMap<>();
        overrideUserAgent = (userAgent != null && !userAgent.trim().isEmpty()) ? userAgent.trim() : null;
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken) throws IOException{
        boolean requiresAuth = link.toLowerCase().contains("actions")
                && link.toLowerCase().contains("github")
                && githubToken != null && !githubToken.isEmpty();

        String tempBase = common.UpdateOptions.tempPath != null && !common.UpdateOptions.tempPath.isEmpty() ? ensureDir(common.UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        String outputFilePath   = getString(fileName);
        String outputTempPath   = outputFilePath + ".temp";

        HttpURLConnection seedConnection;
        try {
            seedConnection = openConnection(link, githubToken, requiresAuth);
        } catch (IOException e) {
            logger.warning("Failed to open connection: " + e.getMessage());
            return false;
        }

        for (int attempt = 1; attempt <= Math.max(2, common.UpdateOptions.maxRetries); attempt++) {
            File rawTmp = new File(rawTempPath);
            File outTmp = new File(outputTempPath);
            cleanupQuietly(rawTmp);
            cleanupQuietly(outTmp);
            try {
                HttpURLConnection connection = openConnection(link, githubToken, requiresAuth);
                int code = 0;
                try { code = connection.getResponseCode(); } catch (IOException ignored) {}
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    int base = Math.max(0, common.UpdateOptions.backoffBaseMs);
                    int max = Math.max(base, common.UpdateOptions.backoffMaxMs);
                    int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new java.util.Random().nextInt(250);
                    if (common.UpdateOptions.debug) logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
                    try { Thread.sleep(delay); } catch (InterruptedException ignored2) { Thread.currentThread().interrupt(); }
                    continue;
                }
                if (!downloadWithVerification(rawTmp, connection)) {
                    logger.warning("Download failed (attempt " + attempt + ") — retrying lenient mode (old-plugin behavior)");
                    try {
                        connection = openConnection(link, githubToken, requiresAuth);
                        if (!downloadLenient(rawTmp, connection)) {
                            continue;
                        }
                    } catch (IOException ex) {
                        continue;
                    }
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
                    logger.warning("Downloaded file is not a valid JAR (attempt " + attempt + ")");
                    continue;
                }

                if (!verifyChecksumIfProvided(outTmp, connection)) {
                    logger.warning("Checksum mismatch from server (attempt " + attempt + ")");
                    continue;
                }

                File target = new File(outputFilePath);
                if (common.UpdateOptions.debug) logger.info("[DEBUG] Ready to install: temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
                if (common.UpdateOptions.ignoreDuplicates && target.exists() && sameDigest(target, outTmp, "MD5")) {
                    cleanupQuietly(outTmp);
                    cleanupQuietly(rawTmp);
                    return true;
                }
                moveReplace(outTmp, target);
                cleanupQuietly(rawTmp);
                return true;
            } catch (IOException e) {
                logger.warning("Failed to download or extract plugin: " + e.getMessage());
            } finally {
                cleanupQuietly(new File(rawTempPath));
                cleanupQuietly(new File(outputTempPath));
            }
        }
        return false;
    }




    private String getString(String fileName) {
        String basePlugins = "plugins/";
        String configuredFilePath = common.UpdateOptions.filePath;
        String configuredUpdatePath = common.UpdateOptions.updatePath;
        if (configuredFilePath != null && !configuredFilePath.isEmpty()) {
            return ensureDir(configuredFilePath) + fileName + ".jar";
        }
        String outputFilePath = basePlugins + fileName + ".jar";
        String updateDir = configuredUpdatePath != null && !configuredUpdatePath.isEmpty() ? ensureDir(configuredUpdatePath) : basePlugins + "update/";
        boolean doesUpdateFolderExist = new File(updateDir).exists();
        if (doesUpdateFolderExist) {
            File directoryFile = new File(basePlugins);
            File[] files = directoryFile.listFiles();
            if (files != null) {
                boolean containsPlugin = false;
                for (File file : files) {
                    if (!file.isDirectory() && file.getName().toLowerCase().contains(fileName.toLowerCase())) {
                        containsPlugin = true;
                        break;
                    }
                }
                if (containsPlugin) {
                    outputFilePath = updateDir + fileName + ".jar";
                }
            }
        }
        return outputFilePath;
    }

    private String ensureDir(String dir) {
        if (!dir.endsWith("/") && !dir.endsWith("\\")) dir = dir + "/";
        new File(dir).mkdirs();
        return dir;
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

            try (InputStream in = zipFile.getInputStream(zipEntry.get());
                 FileOutputStream out = new FileOutputStream(outputFilePath)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.getFD().sync();
            }
            return true;
        }
    }

    public boolean downloadJenkinsPlugin(String link, String fileName){
        String tempBase = common.UpdateOptions.tempPath != null && !common.UpdateOptions.tempPath.isEmpty() ? ensureDir(common.UpdateOptions.tempPath) : "plugins/";
        String rawTempPath = tempBase + fileName + ".download.tmp";
        String outputFilePath = getString(fileName);
        String outputTempPath = outputFilePath + ".temp";
        HttpURLConnection seedConnection;
        try {
            seedConnection = openConnection(link, null, false);
        } catch (IOException e) {
            logger.info("Failed to open connection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        for (int attempt = 1; attempt <= Math.max(2, common.UpdateOptions.maxRetries); attempt++) {
            File rawTmp = new File(rawTempPath);
            File outTmp = new File(outputTempPath);
            cleanupQuietly(rawTmp);
            cleanupQuietly(outTmp);
            try {
                HttpURLConnection connection = openConnection(link, null, false);
                int code = 0;
                try { code = connection.getResponseCode(); } catch (IOException ignored) {}
                if (code == 403 || code == 429 || (code >= 500 && code < 600)) {
                    int base = Math.max(0, common.UpdateOptions.backoffBaseMs);
                    int max = Math.max(base, common.UpdateOptions.backoffMaxMs);
                    int delay = Math.min(max, base * (1 << Math.min(attempt, 10))) + new java.util.Random().nextInt(250);
                    if (common.UpdateOptions.debug) logger.info("[DEBUG] HTTP " + code + " for " + link + ", retry in ~" + delay + "ms (attempt " + attempt + ")");
                    try { Thread.sleep(delay); } catch (InterruptedException ignored2) { Thread.currentThread().interrupt(); }
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
                if (common.UpdateOptions.debug) logger.info("[DEBUG] Ready to install: temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
                if (target.exists() && sameDigest(target, outTmp, "MD5")) {
                    cleanupQuietly(outTmp);
                    cleanupQuietly(rawTmp);
                    return true;
                }
                moveReplace(outTmp, target);
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
        } catch (IOException ignored) {}
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
        if (ua == null && common.UpdateOptions.userAgents != null && !common.UpdateOptions.userAgents.isEmpty()) {
            ua = common.UpdateOptions.userAgents.get(new java.util.Random().nextInt(common.UpdateOptions.userAgents.size()));
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
            java.net.URI uri = java.net.URI.create(link);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? (":" + uri.getPort()) : "");
            connection.setRequestProperty("Referer", origin);
        } catch (Throwable ignored) {}

        if (extraHeaders != null) {
            for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }

        connection.setConnectTimeout(common.UpdateOptions.connectTimeoutMs);
        connection.setReadTimeout(common.UpdateOptions.readTimeoutMs);

        if (common.UpdateOptions.debug) {
            logger.info("[DEBUG] OpenConnection url=" + link + ", auth=" + requiresAuth + ", ua=" + ua);
        }
        return connection;
    }


    private static boolean isGithubishHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
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
            if (expected < 0 || (ce != null && !"identity".equalsIgnoreCase(ce)) || (te != null && "chunked".equalsIgnoreCase(te))) {
                canTrustLength = false;
            }
        } catch (Throwable ignored) {}

        long written = 0L;
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(outFile)) {
            if (common.UpdateOptions.debug) {
                try {
                    logger.info("[DEBUG] HTTP code=" + connection.getResponseCode()
                            + ", type=" + connection.getContentType()
                            + ", length=" + expected
                            + (connection.getHeaderField("Content-Encoding") != null
                            ? ", enc=" + connection.getHeaderField("Content-Encoding") : ""));
                } catch (IOException ignored) {}
            }
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                written += n;
            }
            out.getFD().sync();
        }

        if (canTrustLength && expected >= 0 && written != expected) {
            logger.warning("Content-Length mismatch: expected=" + expected + ", got=" + written);
            cleanupQuietly(outFile);
            return false;
        }
        try (FileInputStream fis = new FileInputStream(outFile)) {
            byte[] probe = new byte[64];
            int n = fis.read(probe);
            String head = (n > 0) ? new String(probe, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
            String t = head.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("<!doctype html") || t.startsWith("<html")) {
                cleanupQuietly(outFile);
                return false;
            }
        } catch (Throwable ignored) {}

        return true;
    }




    private boolean downloadLenient(File outFile, HttpURLConnection connection) {
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int r;
            while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            return false;
        }
    }



    public boolean buildFromGitHubRepo(String repoPath, String fileName, String githubToken) throws IOException {
        String repoApi = "https://api.github.com/repos" + repoPath;
        String defaultBranch = "main";
        try {
            HttpURLConnection conn = openConnection(repoApi, githubToken, githubToken != null && !githubToken.isEmpty());
            JsonNode meta = new ObjectMapper().readTree(conn.getInputStream());
            if (meta.has("default_branch")) {
                defaultBranch = meta.get("default_branch").asText();
            }
        } catch (Exception e) {
            logger.info("Unable to query default branch, falling back to 'main': " + e.getMessage());
        }

        String zipUrl = "https://codeload.github.com" + repoPath + "/zip/refs/heads/" + defaultBranch;
        String rawZip = "plugins/" + fileName + ".repo.tmp.zip";
        File rawZipFile = new File(rawZip);
        cleanupQuietly(rawZipFile);
        try {
            HttpURLConnection conn = openConnection(zipUrl, githubToken, githubToken != null && !githubToken.isEmpty());
            if (!downloadWithVerification(rawZipFile, conn)) {
                logger.info("Failed to download repository zipball for " + repoPath);
                return false;
            }
        } catch (IOException e) {
            logger.info("Failed to download repository zipball: " + e.getMessage());
            return false;
        }

        String tempBase = common.UpdateOptions.tempPath != null && !common.UpdateOptions.tempPath.isEmpty() ? ensureDir(common.UpdateOptions.tempPath) : "plugins/";
        Path workDir = Paths.get(tempBase, "build", fileName + "-" + System.currentTimeMillis());
        Files.createDirectories(workDir);
        try {
            extractZipToDir(rawZipFile.toPath(), workDir);
        } catch (IOException e) {
            logger.info("Failed to extract repository zip: " + e.getMessage());
            cleanupTreeQuietly(workDir);
            cleanupQuietly(rawZipFile);
            return false;
        }

        Path projectRoot = findSingleTopDir(workDir).orElse(workDir);

        boolean built = false;
        try {
            if (hasFile(projectRoot, "gradlew") || hasFile(projectRoot, "gradlew.bat")) {
                built = runGradle(projectRoot, true);
            } else if (hasFile(projectRoot, "mvnw") || hasFile(projectRoot, "mvnw.cmd")) {
                built = runMaven(projectRoot, true);
            } else if (hasFile(projectRoot, "build.gradle") || hasFile(projectRoot, "build.gradle.kts")) {
                built = runGradle(projectRoot, false);
            } else if (hasFile(projectRoot, "pom.xml")) {
                built = runMaven(projectRoot, false);
            } else {
                logger.info("No build files found (Gradle/Maven). Cannot build repo: " + repoPath);
            }
        } catch (Exception e) {
            logger.info("Build failed: " + e.getMessage());
            built = false;
        }

        if (!built) {
            cleanupTreeQuietly(workDir);
            cleanupQuietly(rawZipFile);
            return false;
        }

        try {
            Path jar = selectBuiltPluginJar(projectRoot);
            if (jar == null) {
                logger.info("Could not locate a plugin jar after build.");
                cleanupTreeQuietly(workDir);
                cleanupQuietly(rawZipFile);
                return false;
            }
            File outTmp = new File(getString(fileName) + ".temp");
            cleanupQuietly(outTmp);
            Files.createDirectories(outTmp.getParentFile().toPath());
            Files.copy(jar, outTmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (common.UpdateOptions.debug) logger.info("[DEBUG] Built jar selected: " + jar.toAbsolutePath());
            if (!validateJar(outTmp)) {
                logger.info("Built jar is not a valid plugin jar.");
                cleanupQuietly(outTmp);
                cleanupTreeQuietly(workDir);
                cleanupQuietly(rawZipFile);
                return false;
            }
            File target = new File(getString(fileName));
            if (common.UpdateOptions.debug) logger.info("[DEBUG] Installing built jar: temp=" + outTmp.getAbsolutePath() + " -> target=" + target.getAbsolutePath());
            moveReplace(outTmp, target);
            cleanupTreeQuietly(workDir);
            cleanupQuietly(rawZipFile);
            return true;
        } catch (IOException e) {
            logger.info("Failed to install built jar: " + e.getMessage());
            cleanupTreeQuietly(workDir);
            cleanupQuietly(rawZipFile);
            return false;
        }
    }

    private void extractZipToDir(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile.toFile())))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
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

    private java.util.Optional<Path> findSingleTopDir(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            Path only = null;
            int count = 0;
            for (Path p : ds) {
                count++;
                only = p;
                if (count > 1) return java.util.Optional.empty();
            }
            if (only != null && Files.isDirectory(only)) return java.util.Optional.of(only);
            return java.util.Optional.empty();
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
            String line; while ((line = r.readLine()) != null) { /* optionally log */ }
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
            String line; while ((line = r.readLine()) != null) { /* optionally log */ }
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
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private void cleanupQuietly(File f) {
        if (f != null && f.exists()) {
            try { Files.delete(f.toPath()); } catch (IOException ignored) {}
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
            String sha1   = coalesceHeader(connection, "X-Checksum-SHA1", "X-Checksum-Sha1");
            String md5    = coalesceHeader(connection, "X-Checksum-MD5");
            String etag   = stripQuotes(connection.getHeaderField("ETag"));

            if (notBlank(sha256)) return digestMatches(file, "SHA-256", sha256);
            if (notBlank(sha1))   return digestMatches(file, "SHA-1", sha1);
            if (notBlank(md5))    return digestMatches(file, "MD5", md5);

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
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)); DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buf = new byte[8192];
            while (dis.read(buf) != -1) { /* no-op */ }
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

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private String stripQuotes(String s) { return s == null ? null : s.replace("\"", "").trim(); }
    private boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
