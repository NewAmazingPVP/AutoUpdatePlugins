package common;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.logging.Logger;

final class EntryPathOptions {

    private final String filePath;
    private final String updatePath;
    private final Boolean useUpdateFolder;

    private EntryPathOptions(String filePath, String updatePath, Boolean useUpdateFolder) {
        this.filePath = filePath;
        this.updatePath = updatePath;
        this.useUpdateFolder = useUpdateFolder;
    }

    static EntryPathOptions parse(String rawTail, Logger logger) {
        if (rawTail == null || rawTail.trim().isEmpty()) {
            return new EntryPathOptions(null, null, null);
        }

        String filePath = null;
        String updatePath = null;
        Boolean useUpdateFolder = null;

        String[] parts = rawTail.split("\\|");
        for (String part : parts) {
            if (part == null) continue;
            String token = part.trim();
            if (token.isEmpty()) continue;

            int eq = token.indexOf('=');
            if (eq > 0) {
                String key = normalizeKey(token.substring(0, eq));
                String value = stripWrappingQuotes(token.substring(eq + 1).trim());
                if (value.isEmpty()) {
                    continue;
                }

                switch (key) {
                    case "filepath":
                    case "path":
                    case "targetpath":
                    case "installpath":
                    case "dir":
                    case "directory":
                        filePath = sanitizePath(value, logger, "filePath");
                        break;
                    case "updatepath":
                    case "updatefolder":
                    case "updatedir":
                    case "updatedirectory":
                        updatePath = sanitizePath(value, logger, "updatePath");
                        break;
                    case "useupdatefolder":
                    case "useupdate":
                    case "stageinupdate":
                    case "stagetoupdate":
                        Boolean parsed = parseBoolean(value);
                        if (parsed != null) {
                            useUpdateFolder = parsed;
                        } else if (UpdateOptions.debug && logger != null) {
                            logger.info("[DEBUG] Ignoring invalid useUpdateFolder value '" + value + "'.");
                        }
                        break;
                    default:
                        if (UpdateOptions.debug && logger != null) {
                            logger.info("[DEBUG] Ignoring unknown entry option key '" + token.substring(0, eq).trim() + "'.");
                        }
                        break;
                }
                continue;
            }

            if (filePath == null) {
                filePath = sanitizePath(token, logger, "filePath");
            } else {
                Boolean parsed = parseBoolean(token);
                if (parsed != null) {
                    useUpdateFolder = parsed;
                }
            }
        }

        return new EntryPathOptions(filePath, updatePath, useUpdateFolder);
    }

    String getFilePath() {
        return filePath;
    }

    String getUpdatePath() {
        return updatePath;
    }

    Boolean getUseUpdateFolder() {
        return useUpdateFolder;
    }

    private static String normalizeKey(String key) {
        if (key == null) return "";
        String lower = key.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if ("true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized) || "1".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized) || "0".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String sanitizePath(String rawPath, Logger logger, String label) {
        if (rawPath == null) return null;
        String path = stripWrappingQuotes(rawPath.trim());
        if (path.isEmpty()) return null;
        path = expandUserHome(path);
        try {
            Path normalized = Paths.get(path).normalize();
            String value = normalized.toString();
            return value.isEmpty() ? null : value;
        } catch (InvalidPathException ex) {
            if (UpdateOptions.debug && logger != null) {
                logger.info("[DEBUG] Ignoring invalid " + label + " path '" + rawPath + "': " + ex.getMessage());
            }
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

    private static String stripWrappingQuotes(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}
