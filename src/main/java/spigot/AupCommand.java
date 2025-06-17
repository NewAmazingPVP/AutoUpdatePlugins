package spigot;

import common.PluginUpdater;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class AupCommand implements CommandExecutor {

    private final PluginUpdater pluginUpdater;
    private final File listFile;
    private final FileConfiguration config;

    public AupCommand(PluginUpdater pluginUpdater, File listFile, FileConfiguration config) {
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("autoupdateplugins.manage")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "download":
                download(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "update":
                update(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "add":
                if (args.length >= 3) {
                    addPlugin(sender, args[1], joinArgs(args, 2));
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup add <identifier> <link>");
                }
                break;
            case "remove":
                if (args.length >= 2) {
                    removePlugin(sender, args[1]);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup remove <identifier>");
                }
                break;
            case "list":
                int page = 1;
                if (args.length >= 2) {
                    try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignore) { }
                }
                listPlugins(sender, page);
                break;
            case "enable":
                if (args.length >= 2) {
                    setEnabled(sender, args[1], true);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup enable <identifier>");
                }
                break;
            case "disable":
                if (args.length >= 2) {
                    setEnabled(sender, args[1], false);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup disable <identifier>");
                }
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "AutoUpdatePlugins Commands:");
        sender.sendMessage(ChatColor.AQUA + "/aup download [plugin...]" + ChatColor.GRAY + " - Download specific or all plugins");
        sender.sendMessage(ChatColor.AQUA + "/aup update [plugin...]" + ChatColor.GRAY + " - Update plugins (same as /update)");
        sender.sendMessage(ChatColor.AQUA + "/aup add <identifier> <link>");
        sender.sendMessage(ChatColor.AQUA + "/aup remove <identifier>");
        sender.sendMessage(ChatColor.AQUA + "/aup list [page]");
        sender.sendMessage(ChatColor.AQUA + "/aup enable <identifier>");
        sender.sendMessage(ChatColor.AQUA + "/aup disable <identifier>");
    }

    private void download(CommandSender sender, String[] plugins) {
        Map<String, PluginEntry> entries = loadEntries();
        if (plugins.length == 0) {
            pluginUpdater.readList(listFile, "paper", config.getString("updates.key"));
            sender.sendMessage(ChatColor.GREEN + "Updating all plugins...");
            return;
        }
        for (String name : plugins) {
            PluginEntry entry = entries.get(name);
            if (entry != null) {
                pluginUpdater.updatePlugin("paper", config.getString("updates.key"), name, entry.link);
            } else {
                sender.sendMessage(ChatColor.RED + "Plugin " + name + " not found in list.yml");
            }
        }
    }

    private void update(CommandSender sender, String[] plugins) {
        download(sender, plugins);
    }

    private void addPlugin(CommandSender sender, String name, String link) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            lines.add(name + ": " + link);
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            sender.sendMessage(ChatColor.GREEN + "Added " + name);
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to add plugin: " + e.getMessage());
        }
    }

    private void removePlugin(CommandSender sender, String name) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean found = false;
            for (Iterator<String> it = lines.iterator(); it.hasNext();) {
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
                sender.sendMessage(ChatColor.GREEN + "Removed " + name);
            } else {
                sender.sendMessage(ChatColor.RED + name + " not found in list.yml");
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to remove plugin: " + e.getMessage());
        }
    }

    private void listPlugins(CommandSender sender, int page) {
        List<PluginEntry> entries = new ArrayList<>(loadEntries().values());
        int perPage = 8;
        int pages = (int) Math.ceil(entries.size() / (double) perPage);
        if (page < 1) page = 1;
        if (page > pages) page = pages;
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        sender.sendMessage(ChatColor.AQUA + "Plugins (page " + page + "/" + pages + "):");
        for (int i = start; i < end; i++) {
            PluginEntry e = entries.get(i);
            String color = e.enabled ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            sender.sendMessage(color + e.name + ChatColor.GRAY + " -> " + e.link);
        }
    }

    private void setEnabled(CommandSender sender, String name, boolean enable) {
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
                sender.sendMessage(ChatColor.GREEN + (enable ? "Enabled " : "Disabled ") + name);
            } else {
                sender.sendMessage(ChatColor.RED + name + " not found in list.yml");
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to modify plugin: " + e.getMessage());
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
                String name = trimmed.substring(0, idx).trim();
                String link = trimmed.substring(idx + 1).trim();
                list.add(new PluginEntry(name, link, enabled));
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
