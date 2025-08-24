package common;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GitHubBuild {
    private GitHubBuild() {}

    public static boolean handleGitHubBuild(
            Logger log,
            String repoUrl,
            Path outJar,
            String ghToken
    ) {
        try {
            String u = repoUrl.toLowerCase(Locale.ROOT);
            if (!u.startsWith("https://github.com/") && !u.startsWith("http://github.com/")) {
                log.warning("[AutoUpdatePlugins] [DEBUG] Invalid GitHub repository URL: " + repoUrl);
                return false;
            }
            Repo ref = Repo.parse(repoUrl);
            String def = fetchDefaultBranch(log, ref, ghToken);
            if (def != null) ref = ref.withBranch(def);

            if (tryGitHubReleases(log, ref, outJar, ghToken)) return true;

            Path work = Files.createTempDirectory("aup-github-");
            try {
                Path zip = work.resolve("src.zip");
                String zipUrl = "https://codeload.github.com/" + ref.owner + "/" + ref.name + "/zip/refs/heads/" + ref.branch;
                httpGetToFile(log, zipUrl, zip, null);

                Path repoRoot = work.resolve("repo");
                unzip(zip, repoRoot);

                Path project = repoRoot.resolve(ref.name + "-" + ref.branch);

                boolean built = tryGradleThenMaven(log, project);
                if (built && selectBuiltJarRecursive(log, project, outJar)) {
                    return true;
                }

                if (tryJitPackSmart(log, ref, outJar)) return true;

                log.warning("[AutoUpdatePlugins] [DEBUG] No jar produced for " + repoUrl);
                return false;
            } finally {
                try { deleteRecursive(work); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.warning("[AutoUpdatePlugins] [DEBUG] handleGitHubBuild error: " + e.getMessage());
            return false;
        }
    }

    private static String fetchDefaultBranch(Logger log, Repo ref, String token) {
        try {
            String api = "https://api.github.com/repos/" + ref.owner + "/" + ref.name;
            HttpURLConnection c = open(api, token);
            if (c.getResponseCode() != 200) return null;
            String json = readAll(c.getInputStream());
            String key = "\"default_branch\":\"";
            int i = json.indexOf(key);
            if (i < 0) return null;
            int s = i + key.length();
            int e = json.indexOf('"', s);
            if (e < 0) return null;
            String branch = json.substring(s, e).trim();
            if (branch.length() > 0) {
                log.info("[AutoUpdatePlugins] [DEBUG] Default branch for " + ref.owner + "/" + ref.name + " = " + branch);
                return branch;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static boolean tryGitHubReleases(Logger log, Repo ref, Path out, String token) throws IOException {
        String api = "https://api.github.com/repos/" + ref.owner + "/" + ref.name + "/releases/latest";
        HttpURLConnection conn = open(api, token);
        if (conn.getResponseCode() != 200) {
            log.info("[AutoUpdatePlugins] [DEBUG] No latest release (HTTP " + conn.getResponseCode() + ") for /" + ref.owner + "/" + ref.name);
            return false;
        }
        String json = readAll(conn.getInputStream());
        String jarUrl = findFirstJarUrl(json);
        if (jarUrl == null) {
            log.info("[AutoUpdatePlugins] [DEBUG] No .jar asset in latest release for /" + ref.owner + "/" + ref.name);
            return false;
        }
        log.info("[AutoUpdatePlugins] [DEBUG] Downloading GitHub release asset: " + jarUrl);
        httpGetToFile(log, jarUrl, out, null);
        try {
            if (Files.size(out) < 10 * 1024) throw new IOException("Downloaded asset too small");
        } catch (IOException ex) {
            Files.deleteIfExists(out);
            throw ex;
        }
        return true;
    }

    private static String findFirstJarUrl(String json) {
        int idx = 0;
        while (true) {
            int u = json.indexOf("\"browser_download_url\"", idx);
            if (u < 0) return null;
            int q1 = json.indexOf('"', u + 23);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            String url = json.substring(q1 + 1, q2);
            if (url.toLowerCase(Locale.ENGLISH).endsWith(".jar")) return url;
            idx = q2 + 1;
        }
    }

    // ---------- Wrapper/system builds ----------
    private static boolean tryGradleThenMaven(Logger log, Path project) {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");

        Path gradlew = project.resolve(isWindows ? "gradlew.bat" : "gradlew");
        Path mvnw    = project.resolve(isWindows ? "mvnw.cmd"   : "mvnw");

        // Gradle wrapper
        if (Files.isRegularFile(gradlew)) {
            log.info("[AutoUpdatePlugins] [DEBUG] Using Gradle wrapper: " + gradlew);
            if (runBuild(log, project, gradlew, Arrays.asList("build", "-x", "test"))) return true;
        }
        // Maven wrapper
        if (Files.isRegularFile(mvnw)) {
            log.info("[AutoUpdatePlugins] [DEBUG] Using Maven wrapper: " + mvnw);
            if (runBuild(log, project, mvnw, Arrays.asList("-q", "-DskipTests", "package"))) return true;
        }
        // System tools (if installed)
        if (Files.isRegularFile(project.resolve("build.gradle")) || Files.isRegularFile(project.resolve("build.gradle.kts"))) {
            String gradleCmd = isWindows ? "gradle.bat" : "gradle";
            log.info("[AutoUpdatePlugins] [DEBUG] Trying system Gradle: " + gradleCmd);
            if (runBuild(log, project, Paths.get(gradleCmd), Arrays.asList("build", "-x", "test"))) return true;
        }
        if (Files.isRegularFile(project.resolve("pom.xml"))) {
            String mvnCmd = isWindows ? "mvn.cmd" : "mvn";
            log.info("[AutoUpdatePlugins] [DEBUG] Trying system Maven: " + mvnCmd);
            if (runBuild(log, project, Paths.get(mvnCmd), Arrays.asList("-q", "-DskipTests", "package"))) return true;
        }
        return false;
    }

    private static boolean runBuild(Logger log, Path cwd, Path exe, List<String> args) {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");
        List<String> cmd = new ArrayList<String>();
        if (isWindows) {
            cmd.add("cmd"); cmd.add("/c"); cmd.add(exe.toString());
        } else {
            try { exe.toFile().setExecutable(true); } catch (Exception ignore) {}
            cmd.add(exe.toString());
        }
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            Thread t = new Thread(new Pipe(p.getInputStream(), log));
            t.setDaemon(true);
            t.start();
            boolean finished = p.waitFor(20, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); log.warning("[AutoUpdatePlugins] [DEBUG] Build timed out."); return false; }
            int ec = p.exitValue();
            if (ec != 0) { log.warning("[AutoUpdatePlugins] [DEBUG] Build exited with code " + ec); return false; }
            return true;
        } catch (IOException | InterruptedException e) {
            log.warning("[AutoUpdatePlugins] [DEBUG] Build failed to start: " + e.getMessage());
            return false;
        }
    }

    // Recursively find the biggest reasonable jar
    private static boolean selectBuiltJarRecursive(Logger log, Path project, Path out) {
        final File root = project.toFile();
        final List<File> jars = new ArrayList<File>();
        walk(root, new FileFilter() {
            public boolean accept(File f) {
                String n = f.getName().toLowerCase(Locale.ENGLISH);
                return f.isFile() && n.endsWith(".jar") && n.indexOf("sources") < 0 && n.indexOf("javadoc") < 0 && n.indexOf("tests") < 0;
            }
        }, jars);
        if (jars.isEmpty()) return false;
        File best = jars.get(0);
        for (int i = 1; i < jars.size(); i++) {
            if (jars.get(i).length() > best.length()) best = jars.get(i);
        }
        try {
            log.info("[AutoUpdatePlugins] [DEBUG] Built jar selected: " + best.getAbsolutePath());
            Files.createDirectories(out.getParent());
            Files.copy(best.toPath(), out, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.warning("[AutoUpdatePlugins] [DEBUG] Copy failed: " + e.getMessage());
            return false;
        }
    }

    private static void walk(File dir, FileFilter filter, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, filter, out);
            else if (filter.accept(k)) out.add(k);
        }
    }

    // ---------- JitPack (smart) ----------
    private static boolean tryJitPackSmart(Logger log, Repo ref, Path out) {
        // Version candidates
        String[] vers = new String[] { ref.branch + "-SNAPSHOT", (ref.branch.equals("master") ? "main" : "master") + "-SNAPSHOT" };
        for (int i = 0; i < vers.length; i++) {
            String v = vers[i];
            String base = "https://jitpack.io";
            String api  = base + "/api/builds/com.github/" + ref.owner + "/" + ref.name + "/" + v;
            try {
                HttpURLConnection c = open(api, null);
                int code = c.getResponseCode();
                if (code == 404) {
                    if (!tryTriggerJitPack(log, ref, v)) continue;
                    boolean ready = false;
                    for (int k = 0; k < 10; k++) {
                        try { Thread.sleep(3000); } catch (InterruptedException ignore) {}
                        c = open(api, null);
                        code = c.getResponseCode();
                        if (code == 200) { ready = true; break; }
                    }
                    if (!ready) continue;
                } else if (code != 200) {
                    continue;
                }
                String meta = readAll(c.getInputStream());
                String path = findJsonJarPath(meta);
                if (path == null) {
                    String conv = "/com/github/" + ref.owner + "/" + ref.name + "/" + v + "/" + ref.name + "-" + v + ".jar";
                    path = conv;
                }
                String jarUrl = base + path;
                log.info("[AutoUpdatePlugins] [DEBUG] Trying JitPack jar: " + jarUrl);
                httpGetToFile(log, jarUrl, out, null);
                if (Files.size(out) > 10 * 1024) return true;
            } catch (Exception ignore) {}
        }
        log.info("[AutoUpdatePlugins] [DEBUG] JitPack fallback failed.");
        return false;
    }

    private static boolean tryTriggerJitPack(Logger log, Repo ref, String ver) {
        String url = "https://jitpack.io/com/github/" + ref.owner + "/" + ref.name + "/" + ver + "/" + ref.name + "-" + ver + ".jar";
        try {
            log.info("[AutoUpdatePlugins] [DEBUG] Triggering JitPack build: " + url);
            HttpURLConnection c = open(url, null);
            c.getResponseCode();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String findJsonJarPath(String json) {
        int i = 0;
        while (true) {
            int p = json.indexOf("\"path\":\"", i);
            if (p < 0) return null;
            int s = p + 8;
            int e = json.indexOf('"', s);
            if (e < 0) return null;
            String path = json.substring(s, e);
            if (path.toLowerCase(Locale.ENGLISH).endsWith(".jar")) return path;
            i = e + 1;
        }
    }

    private static void unzip(Path zip, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        InputStream in = Files.newInputStream(zip);
        try {
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                Path out = outDir.resolve(e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    OutputStream os = Files.newOutputStream(out);
                    try {
                        int r;
                        while ((r = zis.read(buf)) != -1) os.write(buf, 0, r);
                    } finally { os.close(); }
                }
                zis.closeEntry();
            }
            zis.close();
        } finally { in.close(); }
    }

    private static void httpGetToFile(Logger log, String url, Path out, String tokenOrNull) throws IOException {
        HttpURLConnection conn = open(url, tokenOrNull);
        int code = conn.getResponseCode();
        if (code / 100 == 3) {
            String loc = conn.getHeaderField("Location");
            if (loc != null) { conn.disconnect(); conn = open(loc, tokenOrNull); }
        }
        int finalCode = conn.getResponseCode();
        if (finalCode != 200) throw new IOException("HTTP " + finalCode + " for " + url);

        Files.createDirectories(out.getParent());
        InputStream is = conn.getInputStream();
        try {
            OutputStream os = Files.newOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
            } finally { os.close(); }
        } finally { is.close(); conn.disconnect(); }
    }

    private static HttpURLConnection open(String url, String tokenOrNull) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "AutoUpdatePlugins/1.0");
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json;q=0.9, */*;q=0.1");
        if (tokenOrNull != null && tokenOrNull.trim().length() > 0 && url.contains("api.github.com")) {
            conn.setRequestProperty("Authorization", "Bearer " + tokenOrNull.trim());
        }
        return conn;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        return new String(baos.toByteArray(), "UTF-8");
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        final List<Path> all = new ArrayList<Path>();
        Files.walk(p).forEach(new java.util.function.Consumer<Path>() {
            public void accept(Path x) { all.add(x); }
        });
        Collections.sort(all, new Comparator<Path>() {
            public int compare(Path a, Path b) { return b.compareTo(a); }
        });
        for (int i = 0; i < all.size(); i++) {
            try { Files.deleteIfExists(all.get(i)); } catch (IOException ignore) {}
        }
    }

    private static final class Repo {
        final String owner, name, branch;
        Repo(String o, String n, String b) { owner = o; name = n; branch = b; }
        Repo withBranch(String b) { return new Repo(owner, name, b); }

        static Repo parse(String url) {
            String u = url.split("\\?")[0].split("#")[0];
            String[] parts = u.replace("https://", "").replace("http://", "").split("/");
            String owner = parts.length > 1 ? parts[1] : "";
            String repo  = parts.length > 2 ? parts[2] : "";
            return new Repo(owner, repo, "master");
        }
    }

    private static final class Pipe implements Runnable {
        private final InputStream in; private final Logger log;
        Pipe(InputStream in, Logger log) { this.in = in; this.log = log; }
        public void run() {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line != null && line.trim().length() != 0)
                        log.info("[AutoUpdatePlugins] [BUILD] " + line);
                }
            } catch (IOException ignore) {} finally {
                try { br.close(); } catch (IOException ignore) {}
            }
        }
    }
}
