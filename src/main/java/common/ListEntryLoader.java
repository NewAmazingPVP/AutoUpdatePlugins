package common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ListEntryLoader {

    public enum SuggestionMode {
        ENABLED,
        DISABLED,
        ALL
    }

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
            String lineWithIndent = normalizedRaw.substring(Math.min(indent, normalizedRaw.length()));
            boolean enabled = true;
            if (lineWithIndent.startsWith("#")) {
                enabled = false;
                String commented = lineWithIndent.substring(1);
                int commentedIndent = leadingIndent(commented);
                indent += Math.max(0, commentedIndent - 1);
                lineWithIndent = commented.substring(Math.min(commentedIndent, commented.length()));
            }

            String line = lineWithIndent.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }

            String name = line.substring(0, idx).trim();
            String rawValue = stripInlineComment(line.substring(idx + 1)).trim();
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

        Iterator<Map.Entry<String, LoadedGroup>> it = groups.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().memberNames.isEmpty()) {
                it.remove();
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
        String trimmed = stripInlineComment(stripBom(value)).trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    public static boolean isAutoUpdateEntry(String value) {
        String normalized = normalizeValue(value);
        int qIdx = normalized.indexOf('?');
        if (qIdx < 0 || qIdx >= normalized.length() - 1) {
            return false;
        }
        String query = normalized.substring(qIdx + 1);
        int pipe = query.indexOf('|');
        if (pipe >= 0) {
            query = query.substring(0, pipe);
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String key = idx == -1 ? part : part.substring(0, idx);
            String val = idx == -1 ? "true" : part.substring(idx + 1);
            if ("auto".equalsIgnoreCase(key.trim())) {
                String lower = val.trim().toLowerCase();
                return lower.isEmpty()
                        || "true".equals(lower)
                        || "yes".equals(lower)
                        || "on".equals(lower)
                        || "1".equals(lower);
            }
        }
        return false;
    }

    public static String uncommentLine(String line) {
        if (line == null) {
            return null;
        }
        int indent = leadingIndent(line);
        if (indent >= line.length() || line.charAt(indent) != '#') {
            return line;
        }
        String prefix = line.substring(0, indent);
        String after = line.substring(indent + 1);
        if (after.startsWith(" ")) {
            after = after.substring(1);
        }
        return prefix + after;
    }

    public static String commentLine(String line) {
        if (line == null) {
            return null;
        }
        int indent = leadingIndent(line);
        if (indent < line.length() && line.charAt(indent) == '#') {
            return line;
        }
        return line.substring(0, indent) + "# " + line.substring(indent);
    }

    public static List<String> selectorSuggestions(File listFile, String current, SuggestionMode mode) {
        String needle = current == null ? "" : current.toLowerCase();
        SuggestionMode effectiveMode = mode != null ? mode : SuggestionMode.ALL;
        LoadedList loadedList = loadList(listFile);
        List<String> suggestions = new ArrayList<>();

        for (LoadedEntry entry : loadedList.entries.values()) {
            if (!matchesMode(entry.enabled, effectiveMode)) {
                continue;
            }
            if (entry.name.toLowerCase().startsWith(needle)) {
                suggestions.add(entry.name);
            }
        }

        for (LoadedGroup group : loadedList.groups.values()) {
            if (!groupMatchesMode(group, loadedList.entries, effectiveMode)) {
                continue;
            }
            if (group.name.toLowerCase().startsWith(needle)) {
                suggestions.add(group.name);
            }
            String explicit = "group:" + group.name;
            if (explicit.toLowerCase().startsWith(needle)) {
                suggestions.add(explicit);
            }
        }
        return suggestions;
    }

    private static boolean groupMatchesMode(LoadedGroup group, Map<String, LoadedEntry> entries, SuggestionMode mode) {
        if (group == null || entries == null) {
            return false;
        }
        if (mode == SuggestionMode.ALL) {
            return !group.memberNames.isEmpty();
        }
        for (String memberName : group.memberNames) {
            LoadedEntry entry = entries.get(memberName);
            if (entry != null && matchesMode(entry.enabled, mode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMode(boolean enabled, SuggestionMode mode) {
        return mode == SuggestionMode.ALL
                || (mode == SuggestionMode.ENABLED && enabled)
                || (mode == SuggestionMode.DISABLED && !enabled);
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

    private static String stripInlineComment(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                } else if (c == '\\' && i + 1 < value.length()) {
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return value.substring(0, i);
            }
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
