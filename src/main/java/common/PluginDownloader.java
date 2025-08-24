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
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = connection.getInputStream();
            out = new java.io.FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int r;
            while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }




    public boolean buildFromGitHubRepo(String repoPath, String fileName, String key) throws IOException {
        if (repoPath == null || repoPath.isEmpty()) throw new IOException("Invalid repo path");
        if (common.UpdateOptions.debug) logger.info("[DEBUG] Starting GitHub build for " + repoPath);

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
                    try { in.close(); } catch (Exception ignored) {}
                }
                String json = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
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
        } catch (Throwable ignored) {}

        if (defaultBranch == null || defaultBranch.trim().isEmpty()) defaultBranch = "main";

        String[] branches = new String[] { defaultBranch, "main", "master" };
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
                if (downloadLenient(zipFile, c)) { gotZip = true; break; }
            } catch (Throwable ignored) {}
        }
        if (!gotZip) {
            logger.warning("Could not download repo zip for " + repoPath);
            throw new IOException("Could not download repo zip for " + repoPath);
        }
        if (common.UpdateOptions.debug) logger.info("[DEBUG] Downloaded repo zip to " + zipFile.getAbsolutePath());

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
        if (common.UpdateOptions.debug) logger.info("[DEBUG] Build root: " + buildRoot.getAbsolutePath());

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

        // 6) Pick the plugin jar (prefer shadow/all/shaded; skip sources/javadoc/original)
        File jar = pickBuiltJar(buildRoot);
        if (jar == null) {
            logger.warning("Could not locate built jar in " + buildRoot);
            throw new IOException("Could not locate built jar in " + buildRoot);
        }

        // 7) Move to update folder
        File out = new File("plugins/update/" + fileName + ".jar");
        if (common.UpdateOptions.debug) logger.info("[DEBUG] Built jar selected: " + jar.getAbsolutePath());
        copyFile(jar, out);
        return true;
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


    // unzip the downloaded repo
    private void unzipTo(File zip, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
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
        java.util.ArrayDeque<File> q = new java.util.ArrayDeque<File>();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) if (f.isDirectory()) q.add(f);
        }
        int depth = 0;
        while (!q.isEmpty() && depth <= 3) {
            int sz = q.size();
            for (int i=0; i<sz; i++) {
                File d = q.poll();
                if (new File(d, "pom.xml").exists()) return d;
                if (new File(d, "build.gradle").exists() || new File(d, "build.gradle.kts").exists()) return d;
                File[] subs = d.listFiles(new java.io.FileFilter(){ public boolean accept(File f){ return f.isDirectory(); }});
                if (subs != null) for (File s : subs) q.add(s);
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

    private void setExecutable(File f) { try { f.setExecutable(true); } catch (Throwable ignored) {} }

    private boolean fileContains(File f) {
        if (!f.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return s.indexOf("shadowJar") >= 0 || s.indexOf("com.github.johnrengelman.shadow") >= 0;
        } catch (IOException e) { return false; }
    }

    private int run(File cwd, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (common.UpdateOptions.debug) logger.info("[DEBUG] " + line);
                }
            } finally {
                try { r.close(); } catch (Exception ignored) {}
            }
            return p.waitFor();
        } catch (Exception e) {
            if (common.UpdateOptions.debug) logger.info("[DEBUG] Build failed to start: " + e.getMessage());
            return -1;
        }
    }

    private File pickBuiltJar(File buildRoot) {
        // collect candidates
        java.util.List<File> candidates = new java.util.ArrayList<File>();
        File libs = new File(buildRoot, "build/libs");
        if (libs.isDirectory()) {
            File[] arr = libs.listFiles(new java.io.FilenameFilter(){ public boolean accept(File d, String n){ return n.endsWith(".jar"); }});
            if (arr != null) java.util.Collections.addAll(candidates, arr);
        }
        File target = new File(buildRoot, "target");
        if (target.isDirectory()) {
            File[] arr = target.listFiles(new java.io.FilenameFilter(){ public boolean accept(File d, String n){ return n.endsWith(".jar"); }});
            if (arr != null) java.util.Collections.addAll(candidates, arr);
        }

        // search submodules (depth <= 3)
        if (candidates.isEmpty()) {
            java.util.ArrayDeque<File> q = new java.util.ArrayDeque<File>();
            q.add(buildRoot);
            int depth = 0;
            while (!q.isEmpty() && depth <= 3) {
                int sz = q.size();
                for (int i=0; i<sz; i++) {
                    File d = q.poll();
                    File lib = new File(d, "build/libs");
                    if (lib.isDirectory()) {
                        File[] arr = lib.listFiles(new java.io.FilenameFilter(){ public boolean accept(File dd, String n){ return n.endsWith(".jar"); }});
                        if (arr != null) java.util.Collections.addAll(candidates, arr);
                    }
                    File tgt = new File(d, "target");
                    if (tgt.isDirectory()) {
                        File[] arr = tgt.listFiles(new java.io.FilenameFilter(){ public boolean accept(File dd, String n){ return n.endsWith(".jar"); }});
                        if (arr != null) java.util.Collections.addAll(candidates, arr);
                    }
                    File[] subs = d.listFiles(new java.io.FileFilter(){ public boolean accept(File f){ return f.isDirectory(); }});
                    if (subs != null) for (File s : subs) q.add(s);
                }
                depth++;
            }
        }

        if (candidates.isEmpty()) return null;

        // filter out sources/javadoc/original/tests
        java.util.List<File> filtered = new java.util.ArrayList<File>(candidates.size());
        for (File f : candidates) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.indexOf("-sources") >= 0) continue;
            if (n.indexOf("-javadoc") >= 0) continue;
            if (n.indexOf("original-") >= 0) continue;
            if (n.indexOf("tests") >= 0) continue;
            if (n.indexOf("test-fixtures") >= 0) continue;
            filtered.add(f);
        }
        if (filtered.isEmpty()) filtered = candidates;

        // score: prefer shadow/all/shaded, then name with "plugin"
        java.util.Collections.sort(filtered, new java.util.Comparator<File>(){
            public int compare(File a, File b) {
                int sa = scoreJar(a.getName().toLowerCase(java.util.Locale.ROOT));
                int sb = scoreJar(b.getName().toLowerCase(java.util.Locale.ROOT));
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
        java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

}
