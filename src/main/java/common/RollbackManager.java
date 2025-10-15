package common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class RollbackManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final ConcurrentHashMap<String, BackupRecord> RECORDS_BY_PLUGIN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BackupRecord> RECORDS_BY_JAR = new ConcurrentHashMap<>();
    private static final List<Pattern> INTERNAL_FILTERS = new CopyOnWriteArrayList<>();
    private static final Object PENDING_LOCK = new Object();
    private static final ThreadLocal<Boolean> REENTRANCY_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile Path rollbackRoot;

    private static final Pattern[] IDENTIFIER_HINTS = new Pattern[]{
            Pattern.compile("(?i)plugin ['\\\"]?([^'\\\"\\s]+?\\.jar)['\\\"]?"),
            Pattern.compile("(?i)plugin ['\\\"]?([^'\\\"\\s]+)['\\\"]?"),
            Pattern.compile("(?i)enabling\\s+([A-Za-z0-9_\\-]+)\\b"),
            Pattern.compile("(?i)disabling\\s+([A-Za-z0-9_\\-]+)\\b"),
            Pattern.compile("\\[([^\\]]+)\\]")
    };

    private RollbackManager() {
    }

    public static void refreshConfiguration(Logger logger) {
        if (!UpdateOptions.rollbackEnabled) {
            INTERNAL_FILTERS.clear();
            return;
        }
        configureRoot(logger);
        compileFilters(logger);
    }

    public static void prepareBackup(Logger logger, String pluginName, Path installTarget) {
        if (!UpdateOptions.rollbackEnabled) return;
        Path normalizedTarget = normalizePath(installTarget);
        if (normalizedTarget == null) return;

        Path activePath = resolveActivePath(normalizedTarget);
        if (activePath == null || !Files.exists(activePath)) {
            return;
        }

        String jarName = fileName(activePath);
        if (jarName == null) jarName = fileName(normalizedTarget);
        if (jarName == null) jarName = pluginName != null ? pluginName : "plugin";

        Path backupPath = createBackup(logger, activePath, jarName);
        if (backupPath == null) return;

        BackupRecord record = new BackupRecord(pluginName, jarName, activePath, normalizedTarget, backupPath, Instant.now().toEpochMilli());
        RECORDS_BY_PLUGIN.put(record.pluginKey, record);
        RECORDS_BY_JAR.put(record.jarKey, record);
        if (UpdateOptions.debug && logger != null) {
            logger.info("[DEBUG] Stored rollback snapshot for " + jarName + " -> " + backupPath);
        }
    }

    public static void markInstalled(String pluginName, Path targetPath) {
        if (!UpdateOptions.rollbackEnabled) return;
        Path normalized = normalizePath(targetPath);
        if (normalized == null) return;
        BackupRecord record = resolveRecord(pluginName, normalized);
        if (record != null) {
            record.setNewJarPath(normalized);
            RECORDS_BY_JAR.put(record.jarKey, record);
        }
    }

    public static void handleLogLine(Logger logger, String platform, String message) {
        if (!UpdateOptions.rollbackEnabled) return;
        if (message == null || message.isEmpty()) return;
        if (Boolean.TRUE.equals(REENTRANCY_GUARD.get())) return;

        try {
            REENTRANCY_GUARD.set(Boolean.TRUE);
            for (Pattern pattern : INTERNAL_FILTERS) {
                if (pattern.matcher(message).find()) {
                    attemptRollback(logger, platform, message);
                    break;
                }
            }
        } finally {
            REENTRANCY_GUARD.set(Boolean.FALSE);
        }
    }

    public static void handleThrowable(Logger logger, String platform, Throwable throwable) {
        if (throwable == null) return;
        String msg = throwable.getMessage();
        if (msg != null && !msg.isEmpty()) {
            handleLogLine(logger, platform, msg);
        }
    }

    public static void processPendingRollbacks(Logger logger, String platform) {
        if (!UpdateOptions.rollbackEnabled) {
            purgePendingFile();
            return;
        }
        List<PendingTask> tasks = loadPending();
        if (tasks.isEmpty()) return;

        List<PendingTask> failures = new ArrayList<>();
        for (PendingTask task : tasks) {
            BackupRecord record = task.toRecord();
            if (!performRollback(record, logger, platform, "Pending rollback replay")) {
                failures.add(task);
            }
        }
        if (failures.isEmpty()) {
            purgePendingFile();
        } else {
            savePending(failures);
        }
    }

    private static void attemptRollback(Logger logger, String platform, String message) {
        Set<String> candidates = extractCandidates(message);
        if (candidates.isEmpty()) return;

        for (String candidate : candidates) {
            BackupRecord record = resolveRecord(candidate, null);
            if (record == null) continue;
            performRollback(record, logger, platform, message);
            break;
        }
    }

    private static boolean performRollback(BackupRecord record, Logger logger, String platform, String triggerMessage) {
        if (record == null) return false;
        synchronized (record) {
            if (record.rollbackTriggered) return true;
            record.rollbackTriggered = true;
        }

        Path backup = record.backupPath;
        if (backup == null || !Files.exists(backup)) {
            if (logger != null) {
                logger.warning("[AutoUpdatePlugins] Rollback requested for " + record.jarKey + " but backup is missing.");
            }
            return false;
        }

        archiveFailedBinary(record, logger);

        boolean restored = false;
        List<Path> targets = record.copyTargets();
        for (Path target : targets) {
            if (target == null) continue;
            try {
                Files.createDirectories(target.getParent());
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                restored = true;
            } catch (IOException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to restore " + record.jarKey + " to " + target, ex);
                }
            }
        }

        if (restored) {
            if (logger != null) {
                logger.info("[AutoUpdatePlugins] Rollback completed for " + record.jarKey + ". Trigger: " + triggerMessage);
                logger.info("[AutoUpdatePlugins] Please restart the " + platform + " server to finalize the rollback.");
            }
            cleanupNewArtifacts(record, logger);
            return true;
        }

        queuePending(record);
        if (logger != null) {
            logger.warning("[AutoUpdatePlugins] Rollback queued for retry (file may be locked): " + record.jarKey);
        }
        return false;
    }

    private static void archiveFailedBinary(BackupRecord record, Logger logger) {
        Path target = record.currentJarPath();
        if (target == null || !Files.exists(target)) return;
        try {
            Path root = ensureRollbackRoot();
            if (root == null) return;
            Path failedDir = root.resolve("failed");
            Files.createDirectories(failedDir);
            String fileBase = sanitizeFileBase(fileName(target));
            if (fileBase.isEmpty()) {
                fileBase = sanitizeFileBase(record.jarKey);
            }
            String stamp = STAMP.format(Instant.now());
            Path dest = failedDir.resolve(fileBase + "-" + stamp + ".failed.jar");
            Files.copy(target, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            if (UpdateOptions.debug && logger != null) {
                logger.log(Level.FINE, "[AutoUpdatePlugins] Failed to archive broken binary for " + record.jarKey, ex);
            }
        }
    }

    private static void cleanupNewArtifacts(BackupRecord record, Logger logger) {
        Path targetPath = record.targetPath;
        if (targetPath != null && !Objects.equals(targetPath, record.activePath)) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ex) {
                if (UpdateOptions.debug && logger != null) {
                    logger.log(Level.FINE, "[AutoUpdatePlugins] Could not remove staged jar " + targetPath, ex);
                }
            }
        }
    }

    private static void queuePending(BackupRecord record) {
        PendingTask task = new PendingTask();
        task.pluginKey = record.pluginKey;
        task.jarKey = record.jarKey;
        task.activePath = record.activePath != null ? record.activePath.toString() : null;
        task.targetPath = record.targetPath != null ? record.targetPath.toString() : null;
        task.backupPath = record.backupPath != null ? record.backupPath.toString() : null;

        synchronized (PENDING_LOCK) {
            List<PendingTask> tasks = loadPending();
            boolean exists = tasks.stream().anyMatch(t -> Objects.equals(t.pluginKey, task.pluginKey) && Objects.equals(t.jarKey, task.jarKey));
            if (!exists) {
                tasks.add(task);
                savePending(tasks);
            }
        }
    }

    private static List<PendingTask> loadPending() {
        Path file = pendingFile();
        if (file == null || !Files.exists(file)) return new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return new ArrayList<>();
            return MAPPER.readValue(bytes, new TypeReference<List<PendingTask>>() {
            });
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void savePending(List<PendingTask> tasks) {
        Path file = pendingFile();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(tasks);
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
        }
    }

    private static void purgePendingFile() {
        Path file = pendingFile();
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static Path pendingFile() {
        Path root = ensureRollbackRoot();
        if (root == null) return null;
        return root.resolve("pending.json");
    }

    private static BackupRecord resolveRecord(String identifier, Path targetPath) {
        if (identifier != null) {
            String normalized = normalizeKey(identifier);
            BackupRecord record = RECORDS_BY_PLUGIN.get(normalized);
            if (record != null) return record;
            record = RECORDS_BY_JAR.get(normalized);
            if (record != null) return record;
        }
        if (targetPath != null) {
            String jar = fileName(targetPath);
            if (jar != null) {
                return RECORDS_BY_JAR.get(normalizeKey(jar));
            }
        }
        return null;
    }

    private static void configureRoot(Logger logger) {
        Path path = null;
        if (UpdateOptions.rollbackPath != null && !UpdateOptions.rollbackPath.trim().isEmpty()) {
            try {
                path = Paths.get(UpdateOptions.rollbackPath.trim());
            } catch (InvalidPathException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[AutoUpdatePlugins] Invalid rollback path '" + UpdateOptions.rollbackPath + "'", ex);
                }
            }
        }
        if (path == null) {
            path = Paths.get("plugins", "aup-rollbacks");
        }
        try {
            Files.createDirectories(path);
            rollbackRoot = path.toAbsolutePath().normalize();
        } catch (IOException ex) {
            rollbackRoot = null;
            if (logger != null) {
                logger.log(Level.WARNING, "[AutoUpdatePlugins] Failed to create rollback directory at " + path, ex);
            }
        }
    }

    private static void compileFilters(Logger logger) {
        INTERNAL_FILTERS.clear();
        List<String> filters = UpdateOptions.rollbackFilters.isEmpty()
                ? defaultFilters()
                : UpdateOptions.rollbackFilters;
        for (String expr : filters) {
            if (expr == null || expr.trim().isEmpty()) continue;
            try {
                INTERNAL_FILTERS.add(Pattern.compile(expr, Pattern.CASE_INSENSITIVE));
            } catch (Exception ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[AutoUpdatePlugins] Invalid rollback filter '" + expr + "': " + ex.getMessage());
                }
            }
        }
        if (INTERNAL_FILTERS.isEmpty()) {
            INTERNAL_FILTERS.addAll(defaultPatterns());
        }
    }

    private static List<String> defaultFilters() {
        return Arrays.asList(
                "Unsupported API version",
                "Could not load plugin",
                "Error occurred while enabling",
                "Unsupported MC version",
                "You are running an unsupported server version"
        );
    }

    private static List<Pattern> defaultPatterns() {
        List<Pattern> defaults = new ArrayList<>();
        for (String expr : defaultFilters()) {
            defaults.add(Pattern.compile(expr, Pattern.CASE_INSENSITIVE));
        }
        return defaults;
    }

    private static Path createBackup(Logger logger, Path source, String jarName) {
        Path root = ensureRollbackRoot();
        if (root == null) return null;
        try {
            String base = sanitizeFileBase(jarName);
            if (base.isEmpty()) base = "plugin";
            Path pluginDir = root.resolve(base);
            Files.createDirectories(pluginDir);
            String stamp = STAMP.format(Instant.now());
            Path backup = pluginDir.resolve(base + "-" + stamp + ".jar");
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            trimBackups(pluginDir);
            return backup;
        } catch (IOException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "[AutoUpdatePlugins] Could not snapshot previous jar for rollback", ex);
            }
            return null;
        }
    }

    private static void trimBackups(Path pluginDir) {
        if (pluginDir == null || UpdateOptions.rollbackMaxCopies <= 0) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
            List<Path> backups = new ArrayList<>();
            for (Path path : stream) backups.add(path);
            backups.sort((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()));
            for (int i = UpdateOptions.rollbackMaxCopies; i < backups.size(); i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Path ensureRollbackRoot() {
        Path root = rollbackRoot;
        if (root != null) return root;
        if (UpdateOptions.rollbackPath != null && !UpdateOptions.rollbackPath.trim().isEmpty()) {
            try {
                root = Paths.get(UpdateOptions.rollbackPath.trim());
            } catch (InvalidPathException ignored) {
                root = null;
            }
        }
        if (root == null) {
            root = Paths.get("plugins", "aup-rollbacks");
        }
        try {
            Files.createDirectories(root);
            rollbackRoot = root.toAbsolutePath().normalize();
        } catch (IOException ignored) {
            rollbackRoot = null;
        }
        return rollbackRoot;
    }

    private static Set<String> extractCandidates(String message) {
        Set<String> candidates = new LinkedHashSet<>();
        for (Pattern hint : IDENTIFIER_HINTS) {
            try {
                java.util.regex.Matcher matcher = hint.matcher(message);
                while (matcher.find()) {
                    String candidate = matcher.group(1);
                    if (candidate == null || candidate.isEmpty()) continue;
                    candidates.add(candidate);
                }
            } catch (Exception ignored) {
            }
        }
        return candidates;
    }

    private static Path resolveActivePath(Path target) {
        if (target == null) return null;
        if (Files.exists(target)) return target;
        Path parent = target.getParent();
        if (parent != null && parent.getFileName() != null) {
            String parentName = parent.getFileName().toString().toLowerCase(Locale.ROOT);
            if ("update".equals(parentName) && parent.getParent() != null) {
                Path candidate = parent.getParent().resolve(target.getFileName());
                if (Files.exists(candidate)) return candidate;
            }
        }
        if (UpdateOptions.updatePath != null && !UpdateOptions.updatePath.trim().isEmpty()) {
            try {
                Path custom = Paths.get(UpdateOptions.updatePath.trim()).toAbsolutePath().normalize();
                if (target.startsWith(custom)) {
                    Path relative = custom.relativize(target);
                    if (relative.getNameCount() >= 1) {
                        Path candidate = Paths.get("plugins").resolve(relative.getFileName());
                        if (Files.exists(candidate)) return candidate.toAbsolutePath().normalize();
                    }
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return null;
    }

    private static Path normalizePath(Path path) {
        if (path == null) return null;
        try {
            return path.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String fileName(Path path) {
        if (path == null) return null;
        Path fn = path.getFileName();
        return fn != null ? fn.toString() : null;
    }

    private static String sanitizeFileBase(String name) {
        if (name == null) return "";
        String base = name;
        if (base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (base.isEmpty()) base = "plugin";
        return base;
    }

    private static String normalizeKey(String value) {
        if (value == null) return null;
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(".jar")) key = key.substring(0, key.length() - 4);
        return key.replace(' ', '_');
    }

    private static class BackupRecord {
        final String pluginKey;
        final String jarKey;
        final Path activePath;
        final Path targetPath;
        final Path backupPath;
        final long timestamp;
        volatile Path newJarPath;
        volatile boolean rollbackTriggered;

        BackupRecord(String pluginName, String jarName, Path activePath, Path targetPath, Path backupPath, long timestamp) {
            this.pluginKey = normalizeKey(pluginName != null ? pluginName : jarName);
            this.jarKey = normalizeKey(jarName);
            this.activePath = activePath;
            this.targetPath = targetPath;
            this.backupPath = backupPath;
            this.timestamp = timestamp;
        }

        void setNewJarPath(Path path) {
            this.newJarPath = path;
        }

        Path currentJarPath() {
            if (newJarPath != null) return newJarPath;
            if (activePath != null) return activePath;
            return targetPath;
        }

        List<Path> copyTargets() {
            List<Path> targets = new ArrayList<>();
            if (activePath != null) targets.add(activePath);
            if (targetPath != null && (activePath == null || !activePath.equals(targetPath))) {
                targets.add(targetPath);
            }
            return targets;
        }
    }

    private static class PendingTask {
        public String pluginKey;
        public String jarKey;
        public String activePath;
        public String targetPath;
        public String backupPath;

        BackupRecord toRecord() {
            Path active = safePath(activePath);
            Path target = safePath(targetPath);
            if (target == null) target = active;
            Path backup = safePath(backupPath);
            BackupRecord record = new BackupRecord(pluginKey, jarKey, active, target, backup, System.currentTimeMillis());
            record.rollbackTriggered = false;
            return record;
        }
    }

    private static Path safePath(String source) {
        if (source == null || source.trim().isEmpty()) return null;
        try {
            return Paths.get(source);
        } catch (InvalidPathException ex) {
            return null;
        }
    }
}
