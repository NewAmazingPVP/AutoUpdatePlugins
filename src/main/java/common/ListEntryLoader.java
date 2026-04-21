package common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ListEntryLoader {

    private ListEntryLoader() {
    }

    public static LoadedList loadList(File listFile) {
        LinkedHashMap<String, LoadedEntry> entries = new LinkedHashMap<>();
        LinkedHashMap<String, LoadedGroup> groups = new LinkedHashMap<>();
        if (listFile == null || !listFile.isFile()) {
            return new LoadedList(entries, groups);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return new LoadedList(entries, groups);
        }

        String currentGroup = null;
        boolean currentGroupEnabled = true;

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String normalizedRaw = stripBom(rawLine);
            if (normalizedRaw.trim().isEmpty()) continue;

            int indent = leadingIndent(normalizedRaw);
            String line = normalizedRaw.trim();

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
            String rawValue = line.substring(idx + 1).trim();
            if (name.isEmpty()) {
                continue;
            }

            if (rawValue.isEmpty()) {
                currentGroup = name;
                currentGroupEnabled = enabled;
                LoadedGroup group = groups.get(name);
                if (group == null) {
                    group = new LoadedGroup(name, enabled);
                    groups.put(name, group);
                }
                continue;
            }

            if (indent == 0) {
                currentGroup = null;
                currentGroupEnabled = true;
            }

            String groupName = (indent > 0 && currentGroup != null) ? currentGroup : null;
            boolean entryEnabled = enabled && (groupName == null || currentGroupEnabled);
            String link = normalizeValue(rawValue);
            if (link.isEmpty()) {
                continue;
            }

            LoadedEntry entry = new LoadedEntry(name, link, entryEnabled, groupName);
            entries.put(name, entry);
            if (groupName != null) {
                LoadedGroup group = groups.get(groupName);
                if (group == null) {
                    group = new LoadedGroup(groupName, currentGroupEnabled);
                    groups.put(groupName, group);
                }
                group.memberNames.add(name);
            }
        }

        return new LoadedList(entries, groups);
    }

    public static LinkedHashMap<String, LoadedEntry> loadEntries(File listFile) {
        return new LinkedHashMap<>(loadList(listFile).entries);
    }

    public static LinkedHashMap<String, String> loadEnabledLinks(File listFile) {
        LinkedHashMap<String, String> links = new LinkedHashMap<>();
        for (Map.Entry<String, LoadedEntry> entry : loadList(listFile).entries.entrySet()) {
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

    private static int leadingIndent(String value) {
        int indent = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                indent++;
            } else if (c == '\t') {
                indent += 4;
            } else {
                break;
            }
        }
        return indent;
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
        public final String group;

        LoadedEntry(String name, String link, boolean enabled, String group) {
            this.name = name;
            this.link = link;
            this.enabled = enabled;
            this.group = group;
        }
    }

    public static final class LoadedGroup {
        public final String name;
        public final boolean enabled;
        public final List<String> memberNames = new ArrayList<>();

        LoadedGroup(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    public static final class LoadedList {
        public final LinkedHashMap<String, LoadedEntry> entries;
        public final LinkedHashMap<String, LoadedGroup> groups;

        LoadedList(LinkedHashMap<String, LoadedEntry> entries, LinkedHashMap<String, LoadedGroup> groups) {
            this.entries = entries;
            this.groups = groups;
        }

        public List<String> groupNames() {
            return Collections.unmodifiableList(new ArrayList<>(groups.keySet()));
        }
    }
}
