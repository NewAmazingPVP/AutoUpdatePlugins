package common;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.representer.StandardRepresenter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

public class ConfigManager {
    private final File configFile;
    private Map<String, Object> configMap;
    private final LoadSettings loadSettings;
    private final DumpSettings dumpSettings;
    private String originalContent;
    private final StandardRepresenter representer;
    private final Map<String, String> commentMap;
    private final Set<String> processedPaths;

    public ConfigManager(File dataFolder, String fileName) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.configFile = new File(dataFolder, fileName);
        this.commentMap = new LinkedHashMap<>();
        this.processedPaths = new HashSet<>();

        this.representer = new StandardRepresenter(DumpSettings.builder().build()) {
            @Override
            protected Node representScalar(Tag tag, String value, ScalarStyle style) {
                if (value.contains("\n")) {
                    return super.representScalar(tag, value, ScalarStyle.LITERAL);
                }
                if (Pattern.compile("[^a-zA-Z0-9._-]").matcher(value).find()) {
                    return super.representScalar(tag, value, ScalarStyle.SINGLE_QUOTED);
                }
                return super.representScalar(tag, value, style);
            }
        };

        this.loadSettings = LoadSettings.builder()
                .setParseComments(true)
                .build();

        this.dumpSettings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setDumpComments(true)
                .setIndent(2)
                .setIndicatorIndent(0)
                .setWidth(4096)
                .build();

        loadConfig();
    }

    private void loadConfig() {
        if (configFile.exists()) {
            try {
                originalContent = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                Load loader = new Load(loadSettings);
                configMap = (Map<String, Object>) loader.loadFromString(originalContent);
                if (configMap == null) {
                    configMap = new HashMap<>();
                }
                extractExistingComments();
            } catch (IOException e) {
                System.err.println("Could not load config: " + e.getMessage());
                configMap = new HashMap<>();
            }
        } else {
            configMap = new HashMap<>();
            saveConfig();
        }
    }

    private void extractExistingComments() {
        if (originalContent == null || originalContent.isEmpty()) {
            return;
        }

        String[] lines = originalContent.split("\n");
        StringBuilder currentComment = new StringBuilder();
        Stack<String> pathStack = new Stack<>();
        int indentLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            int currentIndent = getIndentLevel(line);

            while (!pathStack.isEmpty() && currentIndent <= indentLevel) {
                pathStack.pop();
                indentLevel -= 2;
            }

            if (trimmed.startsWith("#")) {
                if (currentComment.length() > 0) {
                    currentComment.append("\n");
                }
                currentComment.append(line);
            } else if (!trimmed.isEmpty()) {
                if (trimmed.contains(":")) {
                    String key = trimmed.split(":", 2)[0].trim();

                    if (currentIndent > indentLevel) {
                        pathStack.push(key);
                        indentLevel = currentIndent;
                    } else {
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                        pathStack.push(key);
                    }

                    String currentPath = String.join(".", pathStack);

                    if (currentComment.length() > 0) {
                        commentMap.put(currentPath, currentComment.toString());
                        processedPaths.add(currentPath);
                        currentComment = new StringBuilder();
                    }
                }
            }
        }
    }

    private int getIndentLevel(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    public void saveConfig() {
        try {
            Dump dumper = new Dump(dumpSettings, representer);
            String newContent = dumper.dumpToString(configMap);

            String[] lines = newContent.split("\n");
            StringBuilder result = new StringBuilder();
            Stack<String> pathStack = new Stack<>();
            int currentIndent = 0;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                int lineIndent = getIndentLevel(line);

                while (!pathStack.isEmpty() && lineIndent <= currentIndent) {
                    pathStack.pop();
                    currentIndent -= 2;
                }

                if (trimmed.contains(":")) {
                    String key = trimmed.split(":", 2)[0].trim();

                    if (lineIndent > currentIndent) {
                        pathStack.push(key);
                        currentIndent = lineIndent;
                    } else {
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                        pathStack.push(key);
                    }

                    String fullPath = String.join(".", pathStack);
                    String comment = commentMap.get(fullPath);

                    if (comment != null) {
                        result.append(comment).append("\n");
                    }
                }

                result.append(line);
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            }

            Files.write(configFile.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
            originalContent = result.toString();
        } catch (IOException e) {
            System.err.println("Could not save config: " + e.getMessage());
        }
    }

    public Object getOption(String path) {
        return getNestedOption(path);
    }

    public void setOption(String path, Object value) {
        setNestedOption(path, value);
        saveConfig();
    }

    public Object getOptionOrDefault(String path, Object defaultValue) {
        Object value = getNestedOption(path);
        if (value == null) {
            setOption(path, defaultValue);
            return defaultValue;
        }
        return value;
    }

    public void addDefault(String path, Object value) {
        if (getNestedOption(path) == null) {
            setOption(path, value);
        }
    }

    public void addDefault(String path, Object value, String comment) {
        if (!processedPaths.contains(path)) {
            if (getNestedOption(path) == null) {
                setOption(path, value);
            }
            setComment(path, comment);
            processedPaths.add(path);
        }
    }

    public void setComment(String path, String comment) {
        String formattedComment = formatComment(comment);
        commentMap.put(path, formattedComment);
    }

    public String getComment(String path) {
        return commentMap.get(path);
    }

    private String formatComment(String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            return "";
        }

        StringBuilder formatted = new StringBuilder();
        for (String line : comment.split("\n")) {
            if (!line.trim().startsWith("#")) {
                formatted.append("# ");
            }
            formatted.append(line).append("\n");
        }
        return formatted.toString().trim();
    }

    public boolean contains(String path) {
        return getNestedOption(path) != null;
    }

    public String getString(String path) {
        Object value = getNestedOption(path);
        return value != null ? value.toString() : null;
    }

    public int getInt(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public double getDouble(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    public boolean getBoolean(String path) {
        Object value = getNestedOption(path);
        return value instanceof Boolean ? (Boolean) value : false;
    }

    public byte getByte(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).byteValue() : 0;
    }

    public short getShort(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).shortValue() : 0;
    }

    public long getLong(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public float getFloat(String path) {
        Object value = getNestedOption(path);
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    public char getChar(String path) {
        Object value = getNestedOption(path);
        return value instanceof Character ? (Character) value : '\u0000';
    }

    public List<Byte> getByteList(String path) {
        List<?> list = getList(path);
        List<Byte> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).byteValue());
            }
        }
        return result;
    }

    public List<Short> getShortList(String path) {
        List<?> list = getList(path);
        List<Short> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).shortValue());
            }
        }
        return result;
    }

    public Set<String> getKeys(String path) {
        Object section = getNestedOption(path);
        if (section instanceof Map) {
            return ((Map<String, Object>) section).keySet();
        }
        return Collections.emptySet();
    }

    public Map<String, Object> getSection(String path) {
        Object section = getNestedOption(path);
        if (section instanceof Map) {
            return (Map<String, Object>) section;
        }
        return Collections.emptyMap();
    }

    public List<Integer> getIntList(String path) {
        List<?> list = getList(path);
        List<Integer> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).intValue());
            }
        }
        return result;
    }

    public List<Long> getLongList(String path) {
        List<?> list = getList(path);
        List<Long> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).longValue());
            }
        }
        return result;
    }

    public List<Float> getFloatList(String path) {
        List<?> list = getList(path);
        List<Float> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).floatValue());
            }
        }
        return result;
    }

    public List<Double> getDoubleList(String path) {
        List<?> list = getList(path);
        List<Double> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Number) {
                result.add(((Number) object).doubleValue());
            }
        }
        return result;
    }

    public List<Boolean> getBooleanList(String path) {
        List<?> list = getList(path);
        List<Boolean> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Boolean) {
                result.add((Boolean) object);
            }
        }
        return result;
    }

    public List<Character> getCharList(String path) {
        List<?> list = getList(path);
        List<Character> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof Character) {
                result.add((Character) object);
            }
        }
        return result;
    }

    public List<String> getStringList(String path) {
        List<?> list = getList(path);
        List<String> result = new ArrayList<>();
        for (Object object : list) {
            if (object instanceof String) {
                result.add((String) object);
            }
        }
        return result;
    }

    public List<?> getList(String path) {
        Object value = getNestedOption(path);
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private Object getNestedOption(String path) {
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;
        for (int i = 0; i < keys.length - 1; i++) {
            Object nested = currentMap.get(keys[i]);
            if (nested instanceof Map) {
                currentMap = (Map<String, Object>) nested;
            } else {
                return null;
            }
        }
        return currentMap.get(keys[keys.length - 1]);
    }

    private void setNestedOption(String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;
        for (int i = 0; i < keys.length - 1; i++) {
            currentMap = (Map<String, Object>) currentMap.computeIfAbsent(keys[i], k -> new HashMap<>());
        }
        currentMap.put(keys[keys.length - 1], value);
    }
}