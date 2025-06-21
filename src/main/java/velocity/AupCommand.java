package velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import common.PluginUpdater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class AupCommand implements SimpleCommand {

    private final PluginUpdater pluginUpdater;
    private final File listFile;
    private final String token;

    public AupCommand(PluginUpdater pluginUpdater, File listFile, String token) {
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.token = token;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("autoupdateplugins.manage");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(source);
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "download":
                download(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "update":
                download(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "add":
                if (args.length >= 3) {
                    addPlugin(source, args[1], joinArgs(args, 2));
                } else {
                    source.sendMessage(Component.text("Usage: /aup add <identifier> <link>").color(NamedTextColor.RED));
                }
                break;
            case "remove":
                if (args.length >= 2) {
                    removePlugin(source, args[1]);
                } else {
                    source.sendMessage(Component.text("Usage: /aup remove <identifier>").color(NamedTextColor.RED));
                }
                break;
            case "list":
                int page = 1;
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
                }
                listPlugins(source, page);
                break;
            case "enable":
                if (args.length >= 2) {
                    setEnabled(source, args[1], true);
                } else {
                    source.sendMessage(Component.text("Usage: /aup enable <identifier>").color(NamedTextColor.RED));
                }
                break;
            case "disable":
                if (args.length >= 2) {
                    setEnabled(source, args[1], false);
                } else {
                    source.sendMessage(Component.text("Usage: /aup disable <identifier>").color(NamedTextColor.RED));
                }
                break;
            default:
                sendHelp(source);
                break;
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("AutoUpdatePlugins Commands:").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup download [plugin...]").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup update [plugin...]").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup add <identifier> <link>").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup remove <identifier>").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup list [page]").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup enable <identifier>").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("/aup disable <identifier>").color(NamedTextColor.AQUA));
    }

    private void download(CommandSource source, String[] plugins) {
        Map<String, PluginEntry> entries = loadEntries();
        if (plugins.length == 0) {
            pluginUpdater.readList(listFile, "velocity", token);
            source.sendMessage(Component.text("Updating all plugins...").color(NamedTextColor.GREEN));
            return;
        }
        for (String name : plugins) {
            PluginEntry entry = entries.get(name);
            if (entry != null) {
                pluginUpdater.updatePlugin("velocity", token, name, entry.link);
            } else {
                source.sendMessage(Component.text("Plugin " + name + " not found in list.yml").color(NamedTextColor.RED));
            }
        }
    }

    private void addPlugin(CommandSource source, String name, String link) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            lines.add(name + ": " + link);
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            source.sendMessage(Component.text("Added " + name).color(NamedTextColor.GREEN));
        } catch (IOException e) {
            source.sendMessage(Component.text("Failed to add plugin: " + e.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void removePlugin(CommandSource source, String name) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean found = false;
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                String trimmed = line.trim();
                boolean commented = trimmed.startsWith("#");
                if (commented) trimmed = trimmed.substring(1).trim();
                if (trimmed.startsWith(name + ":")) {
                    it.remove();
                    found = true;
                }
            }
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            if (found) {
                source.sendMessage(Component.text("Removed " + name).color(NamedTextColor.GREEN));
            } else {
                source.sendMessage(Component.text(name + " not found in list.yml").color(NamedTextColor.RED));
            }
        } catch (IOException e) {
            source.sendMessage(Component.text("Failed to remove plugin: " + e.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void listPlugins(CommandSource source, int page) {
        List<PluginEntry> entries = new ArrayList<>(loadEntries().values());
        int perPage = 8;
        int pages = (int) Math.ceil(entries.size() / (double) perPage);
        if (page < 1) page = 1;
        if (page > pages) page = pages;
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        source.sendMessage(Component.text("Plugins (page " + page + "/" + pages + "):").color(NamedTextColor.AQUA));
        for (int i = start; i < end; i++) {
            PluginEntry e = entries.get(i);
            NamedTextColor color = e.enabled ? NamedTextColor.GREEN : NamedTextColor.RED;
            source.sendMessage(Component.text(e.name + " -> " + e.link).color(color));
        }
    }

    private void setEnabled(CommandSource source, String name, boolean enable) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                boolean commented = trimmed.startsWith("#");
                String compare = commented ? trimmed.substring(1).trim() : trimmed;
                if (compare.startsWith(name + ":")) {
                    found = true;
                    if (enable && commented) {
                        lines.set(i, line.replaceFirst("#\\s*", ""));
                    } else if (!enable && !commented) {
                        lines.set(i, "# " + line);
                    }
                }
            }
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            if (found) {
                source.sendMessage(Component.text((enable ? "Enabled " : "Disabled ") + name).color(NamedTextColor.GREEN));
            } else {
                source.sendMessage(Component.text(name + " not found in list.yml").color(NamedTextColor.RED));
            }
        } catch (IOException e) {
            source.sendMessage(Component.text("Failed to modify plugin: " + e.getMessage()).color(NamedTextColor.RED));
        }
    }

    private Map<String, PluginEntry> loadEntries() {
        List<PluginEntry> list = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                boolean enabled = true;
                if (trimmed.startsWith("#")) {
                    enabled = false;
                    trimmed = trimmed.substring(1).trim();
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int idx = trimmed.indexOf(":");
                if (idx == -1) continue;
                String n = trimmed.substring(0, idx).trim();
                String link = trimmed.substring(idx + 1).trim();
                list.add(new PluginEntry(n, link, enabled));
            }
        } catch (IOException ignored) {}
        Map<String, PluginEntry> map = new LinkedHashMap<>();
        for (PluginEntry e : list) {
            map.put(e.name, e);
        }
        return map;
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        List<String> completions = new ArrayList<>();
        CommandSource source = invocation.source();
        if (!source.hasPermission("autoupdateplugins.manage")) {
            return completions;
        }
        String[] args = invocation.arguments();
        String[] subs = {"download", "update", "add", "remove", "list", "enable", "disable"};
        if (args.length == 0) {
            completions.addAll(Arrays.asList(subs));
        } else if (args.length == 1) {
            String current = args[0].toLowerCase();
            for (String s : subs) {
                if (s.startsWith(current)) completions.add(s);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (Arrays.asList("download", "update", "remove", "enable", "disable").contains(sub)) {
                Map<String, PluginEntry> entries = loadEntries();
                String current = args[1].toLowerCase();
                for (String name : entries.keySet()) {
                    if (name.toLowerCase().startsWith(current)) completions.add(name);
                }
            } else if ("list".equals(sub)) {
                int pages = (int) Math.ceil(loadEntries().size() / 8.0);
                for (int i = 1; i <= pages; i++) {
                    String p = Integer.toString(i);
                    if (p.startsWith(args[1])) completions.add(p);
                }
            }
        }
        return completions;
    }

    private static class PluginEntry {
        final String name;
        final String link;
        final boolean enabled;
        PluginEntry(String name, String link, boolean enabled) {
            this.name = name;
            this.link = link;
            this.enabled = enabled;
        }
    }
}

