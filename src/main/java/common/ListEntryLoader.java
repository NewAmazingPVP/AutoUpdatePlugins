package common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ListEntryLoader {

    private ListEntryLoader() {
    }

    public static LinkedHashMap<String, LoadedEntry> loadEntries(File listFile) {
        LinkedHashMap<String, LoadedEntry> entries = new LinkedHashMap<>();
        if (listFile == null || !listFile.isFile()) {
            return entries;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return entries;
        }

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = stripBom(rawLine).trim();
            if (line.isEmpty()) continue;

            boolean enabled = true;
            if (line.startsWith("#")) {
                enabled = false;
                line = line.substring(1).trim();
            }

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }

            String name = line.substring(0, idx).trim();
            String link = normalizeValue(line.substring(idx + 1));
            if (name.isEmpty() || link.isEmpty()) {
                continue;
            }
            entries.put(name, new LoadedEntry(name, link, enabled));
        }

        return entries;
    }

    public static LinkedHashMap<String, String> loadEnabledLinks(File listFile) {
        LinkedHashMap<String, String> links = new LinkedHashMap<>();
        for (Map.Entry<String, LoadedEntry> entry : loadEntries(listFile).entrySet()) {
            LoadedEntry value = entry.getValue();
            if (value.enabled) {
                links.put(entry.getKey(), value.link);
            }
        }
        return links;
    }

    public static String normalizeValue(String value) {
        if (value == null) return "";
        String trimmed = stripBom(value).trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        if (value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    public static final class LoadedEntry {
        public final String name;
        public final String link;
        public final boolean enabled;

        LoadedEntry(String name, String link, boolean enabled) {
            this.name = name;
            this.link = link;
            this.enabled = enabled;
        }
    }
}
