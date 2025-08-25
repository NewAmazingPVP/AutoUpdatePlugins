package spigot;

import common.ConfigManager;
import common.PluginUpdater;
import common.UpdateOptions;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

public class AupCommand implements CommandExecutor, TabCompleter {

    private final PluginUpdater pluginUpdater;
    private final File listFile;
    private final FileConfiguration config;
    private final Supplier<String> keySupplier;
    private final ConfigManager cfgMgr;
    private final Runnable reloadAction;

    public AupCommand(PluginUpdater pluginUpdater, File listFile, FileConfiguration config, Supplier<String> keySupplier, ConfigManager cfgMgr, Runnable reloadAction) {
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.config = config;
        this.keySupplier = keySupplier;
        this.cfgMgr = cfgMgr;
        this.reloadAction = reloadAction;
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
            case "debug":
                toggleDebug(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "add":
                if (args.length >= 3) {
                    addPlugin(sender, args[1], joinArgs(args, 2));
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup add <identifier> <link>");
                }
                break;
            case "stop":
                stopUpdating(sender);
                break;
            case "reload":
                reloadConfig(sender);
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
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignore) {
                    }
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
        sender.sendMessage(ChatColor.AQUA + "/aup stop" + ChatColor.GRAY + " - Stop current updating process");
        sender.sendMessage(ChatColor.AQUA + "/aup reload" + ChatColor.GRAY + " - Reload plugin configuration");
        sender.sendMessage(ChatColor.AQUA + "/aup debug <on|off|toggle|status>" + ChatColor.GRAY + " - Verbose debug logging");
        sender.sendMessage(ChatColor.AQUA + "/aup add <identifier> <link>");
        sender.sendMessage(ChatColor.AQUA + "/aup remove <identifier>");
        sender.sendMessage(ChatColor.AQUA + "/aup list [page]");
        sender.sendMessage(ChatColor.AQUA + "/aup enable <identifier>");
        sender.sendMessage(ChatColor.AQUA + "/aup disable <identifier>");
    }

    private void stopUpdating(CommandSender sender) {
        boolean stopped = pluginUpdater.stopUpdates();
        if (stopped) sender.sendMessage(ChatColor.YELLOW + "Stop requested. In-flight downloads may complete.");
        else sender.sendMessage(ChatColor.RED + "No update is currently running.");
    }

    private void reloadConfig(CommandSender sender) {
        try {
            if (reloadAction != null) reloadAction.run();
        } catch (Throwable ignored) {
        }
        sender.sendMessage(ChatColor.GREEN + "AutoUpdatePlugins configuration reloaded.");
    }

    private void toggleDebug(CommandSender sender, String[] args) {
        boolean current = UpdateOptions.debug;
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.AQUA + "Debug is currently " + (current ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            return;
        }
        boolean next = current;
        if ("on".equalsIgnoreCase(args[0])) next = true;
        else if ("off".equalsIgnoreCase(args[0])) next = false;
        else if ("toggle".equalsIgnoreCase(args[0])) next = !current;
        else {
            sender.sendMessage(ChatColor.RED + "Usage: /aup debug <on|off|toggle|status>");
            return;
        }
        UpdateOptions.debug = next;
        try {
            cfgMgr.setOption("behavior.debug", next);
        } catch (Throwable ignored) {
        }
        sender.sendMessage(ChatColor.AQUA + "Debug is now " + (next ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
    }

    private void download(CommandSender sender, String[] plugins) {
        Map<String, PluginEntry> entries = loadEntries();
        if (plugins.length == 0) {
            if (pluginUpdater.isUpdating()) {
                sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
                return;
            }
            pluginUpdater.readList(listFile, serverPlatform(), keySupplier.get());
            sender.sendMessage(ChatColor.GREEN + "Updating all plugins...");
            return;
        }
        for (String name : plugins) {
            PluginEntry entry = entries.get(name);
            if (entry != null) {
                pluginUpdater.updatePlugin(serverPlatform(), keySupplier.get(), name, entry.link);
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
            for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
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
        } catch (IOException ignored) {
        }
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("autoupdateplugins.manage")) {
            return completions;
        }
        String[] subs = {"download", "update", "stop", "reload", "add", "remove", "list", "enable", "disable", "debug"};
        if (args.length == 1) {
            String current = args[0].toLowerCase();
            for (String s : subs) {
                if (s.startsWith(current)) completions.add(s);
            }
            return completions;
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

    private String serverPlatform() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return "folia";
        } catch (Throwable ignored) {
        }
        try {
            String v = Bukkit.getVersion();
            if (v != null && v.toLowerCase().contains("folia")) return "folia";
        } catch (Throwable ignored) {
        }
        return "paper";
    }
}
