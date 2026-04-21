package spigot;

import common.ConfigManager;
import common.ListEntryLoader;
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
    private final Runnable updateAllAction;

    public AupCommand(PluginUpdater pluginUpdater, File listFile, FileConfiguration config, Supplier<String> keySupplier, ConfigManager cfgMgr, Runnable reloadAction, Runnable updateAllAction) {
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.config = config;
        this.keySupplier = keySupplier;
        this.cfgMgr = cfgMgr;
        this.reloadAction = reloadAction;
        this.updateAllAction = updateAllAction;
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
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "download":
            case "update":
                install(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "check":
                check(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "pending":
                showPending(sender);
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
                    removePlugins(sender, args[1]);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup remove <identifier|group>");
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
                    sender.sendMessage(ChatColor.RED + "Usage: /aup enable <identifier|group>");
                }
                break;
            case "disable":
                if (args.length >= 2) {
                    setEnabled(sender, args[1], false);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /aup disable <identifier|group>");
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
        sender.sendMessage(ChatColor.AQUA + "/aup update [plugin|group...]" + ChatColor.GRAY + " - Install specific or all plugins");
        sender.sendMessage(ChatColor.AQUA + "/aup check [plugin|group...]" + ChatColor.GRAY + " - Check for updates without installing");
        sender.sendMessage(ChatColor.AQUA + "/aup pending" + ChatColor.GRAY + " - Show pending manual updates");
        sender.sendMessage(ChatColor.AQUA + "/aup stop" + ChatColor.GRAY + " - Stop current updating process");
        sender.sendMessage(ChatColor.AQUA + "/aup reload" + ChatColor.GRAY + " - Reload plugin configuration");
        sender.sendMessage(ChatColor.AQUA + "/aup debug <on|off|toggle|status>" + ChatColor.GRAY + " - Verbose debug logging");
        sender.sendMessage(ChatColor.AQUA + "/aup add <identifier> <link>");
        sender.sendMessage(ChatColor.AQUA + "/aup remove <identifier|group>");
        sender.sendMessage(ChatColor.AQUA + "/aup list [page]");
        sender.sendMessage(ChatColor.AQUA + "/aup enable <identifier|group>");
        sender.sendMessage(ChatColor.AQUA + "/aup disable <identifier|group>");
        sender.sendMessage(ChatColor.GRAY + "Use group:<name> to target a group explicitly.");
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

    private void install(CommandSender sender, String[] selectors) {
        if (pluginUpdater.isUpdating()) {
            sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
            return;
        }
        if (selectors.length == 0) {
            if (updateAllAction != null) {
                updateAllAction.run();
            } else {
                pluginUpdater.updateList(listFile, serverPlatform(), keySupplier.get());
            }
            sender.sendMessage(ChatColor.GREEN + "Installing all plugin updates...");
            return;
        }

        Map<String, PluginEntry> resolved = resolveEntries(sender, selectors);
        for (PluginEntry entry : resolved.values()) {
            pluginUpdater.updatePlugin(serverPlatform(), keySupplier.get(), entry.name, entry.link);
        }
    }

    private void check(CommandSender sender, String[] selectors) {
        if (pluginUpdater.isUpdating()) {
            sender.sendMessage(ChatColor.RED + "An update is already in progress. Please wait.");
            return;
        }
        if (selectors.length == 0) {
            pluginUpdater.checkList(listFile, serverPlatform(), keySupplier.get());
            sender.sendMessage(ChatColor.AQUA + "Update check started.");
            return;
        }

        Map<String, PluginEntry> resolved = resolveEntries(sender, selectors);
        for (PluginEntry entry : resolved.values()) {
            pluginUpdater.checkPlugin(serverPlatform(), keySupplier.get(), entry.name, entry.link);
        }
        if (!resolved.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "Checking " + resolved.size() + " plugin(s) for updates.");
        }
    }

    private void showPending(CommandSender sender) {
        Map<String, PluginUpdater.PendingUpdate> pending = pluginUpdater.getPendingUpdates();
        if (pending.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No pending updates.");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "Pending updates:");
        for (PluginUpdater.PendingUpdate update : pending.values()) {
            sender.sendMessage(ChatColor.YELLOW + update.pluginName + ChatColor.GRAY + " -> " + update.targetPath);
        }
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

    private void removePlugins(CommandSender sender, String selector) {
        Map<String, PluginEntry> resolved = resolveEntries(sender, new String[]{selector});
        if (resolved.isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>(resolved.keySet());
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                String line = it.next();
                String trimmed = line.trim();
                boolean commented = trimmed.startsWith("#");
                String compare = commented ? trimmed.substring(1).trim() : trimmed;
                for (String name : names) {
                    if (compare.startsWith(name + ":")) {
                        it.remove();
                        changed = true;
                        break;
                    }
                }
            }
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            if (changed) {
                sender.sendMessage(ChatColor.GREEN + "Removed " + names.size() + " plugin(s).");
            } else {
                sender.sendMessage(ChatColor.RED + "Nothing matched in list.yml");
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to remove plugin: " + e.getMessage());
        }
    }

    private void listPlugins(CommandSender sender, int page) {
        List<PluginEntry> entries = new ArrayList<>(loadEntries().values());
        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "list.yml is empty.");
            return;
        }
        int perPage = 8;
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        if (page < 1) page = 1;
        if (page > pages) page = pages;
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        sender.sendMessage(ChatColor.AQUA + "Plugins (page " + page + "/" + pages + "):");
        for (int i = start; i < end; i++) {
            PluginEntry entry = entries.get(i);
            String color = entry.enabled ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
            String label = entry.group != null && !entry.group.isEmpty()
                    ? "[" + entry.group + "] " + entry.name
                    : entry.name;
            sender.sendMessage(color + label + ChatColor.GRAY + " -> " + entry.link);
        }
    }

    private void setEnabled(CommandSender sender, String selector, boolean enable) {
        Map<String, PluginEntry> resolved = resolveEntries(sender, new String[]{selector});
        if (resolved.isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>(resolved.keySet());
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                boolean commented = trimmed.startsWith("#");
                String compare = commented ? trimmed.substring(1).trim() : trimmed;
                for (String name : names) {
                    if (compare.startsWith(name + ":")) {
                        if (enable && commented) {
                            lines.set(i, line.replaceFirst("#\\s*", ""));
                            changed = true;
                        } else if (!enable && !commented) {
                            lines.set(i, "# " + line);
                            changed = true;
                        }
                        break;
                    }
                }
            }
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            if (changed) {
                sender.sendMessage(ChatColor.GREEN + (enable ? "Enabled " : "Disabled ") + names.size() + " plugin(s).");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "No changes were needed.");
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to modify plugin: " + e.getMessage());
        }
    }

    private Map<String, PluginEntry> resolveEntries(CommandSender sender, String[] selectors) {
        ListEntryLoader.LoadedList loadedList = ListEntryLoader.loadList(listFile);
        Map<String, PluginEntry> entries = toPluginEntries(loadedList);
        LinkedHashMap<String, PluginEntry> resolved = new LinkedHashMap<>();
        for (String selector : selectors) {
            if (!addSelectorMatches(resolved, loadedList, entries, selector)) {
                sender.sendMessage(ChatColor.RED + "Target " + selector + " not found in list.yml");
            }
        }
        return resolved;
    }

    private boolean addSelectorMatches(Map<String, PluginEntry> resolved, ListEntryLoader.LoadedList loadedList, Map<String, PluginEntry> entries, String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            return false;
        }
        PluginEntry direct = findEntry(entries, selector);
        if (direct != null) {
            resolved.put(direct.name, direct);
            return true;
        }

        String explicitGroup = null;
        if (selector.regionMatches(true, 0, "group:", 0, "group:".length())) {
            explicitGroup = selector.substring("group:".length()).trim();
        } else if (findGroup(loadedList, selector) != null) {
            explicitGroup = selector.trim();
        }

        if (explicitGroup == null || explicitGroup.isEmpty()) {
            return false;
        }

        ListEntryLoader.LoadedGroup group = findGroup(loadedList, explicitGroup);
        if (group == null) {
            return false;
        }
        for (String memberName : group.memberNames) {
            PluginEntry entry = entries.get(memberName);
            if (entry != null) {
                resolved.put(entry.name, entry);
            }
        }
        return true;
    }

    private Map<String, PluginEntry> loadEntries() {
        return toPluginEntries(ListEntryLoader.loadList(listFile));
    }

    private Map<String, PluginEntry> toPluginEntries(ListEntryLoader.LoadedList loadedList) {
        Map<String, PluginEntry> map = new LinkedHashMap<>();
        for (ListEntryLoader.LoadedEntry entry : loadedList.entries.values()) {
            map.put(entry.name, new PluginEntry(entry.name, entry.link, entry.enabled, entry.group));
        }
        return map;
    }

    private PluginEntry findEntry(Map<String, PluginEntry> entries, String name) {
        PluginEntry direct = entries.get(name);
        if (direct != null) {
            return direct;
        }
        for (PluginEntry entry : entries.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    private ListEntryLoader.LoadedGroup findGroup(ListEntryLoader.LoadedList loadedList, String name) {
        ListEntryLoader.LoadedGroup direct = loadedList.groups.get(name);
        if (direct != null) {
            return direct;
        }
        for (ListEntryLoader.LoadedGroup group : loadedList.groups.values()) {
            if (group.name.equalsIgnoreCase(name)) {
                return group;
            }
        }
        return null;
    }

    private List<String> selectorSuggestions(String current) {
        String needle = current == null ? "" : current.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        ListEntryLoader.LoadedList loadedList = ListEntryLoader.loadList(listFile);
        for (String name : loadedList.entries.keySet()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                suggestions.add(name);
            }
        }
        for (String groupName : loadedList.groups.keySet()) {
            if (groupName.toLowerCase(Locale.ROOT).startsWith(needle)) {
                suggestions.add(groupName);
            }
            String explicit = "group:" + groupName;
            if (explicit.toLowerCase(Locale.ROOT).startsWith(needle)) {
                suggestions.add(explicit);
            }
        }
        return suggestions;
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
        final String group;

        PluginEntry(String name, String link, boolean enabled, String group) {
            this.name = name;
            this.link = link;
            this.enabled = enabled;
            this.group = group;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("autoupdateplugins.manage")) {
            return completions;
        }
        String[] subs = {"download", "update", "check", "pending", "stop", "reload", "add", "remove", "list", "enable", "disable", "debug"};
        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            for (String sub : subs) {
                if (sub.startsWith(current)) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Arrays.asList("download", "update", "check", "remove", "enable", "disable").contains(sub)) {
                completions.addAll(selectorSuggestions(args[1]));
            } else if ("list".equals(sub)) {
                int pages = Math.max(1, (int) Math.ceil(loadEntries().size() / 8.0));
                for (int i = 1; i <= pages; i++) {
                    String page = Integer.toString(i);
                    if (page.startsWith(args[1])) completions.add(page);
                }
            }
        } else if (args.length > 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Arrays.asList("download", "update", "check").contains(sub)) {
                completions.addAll(selectorSuggestions(args[args.length - 1]));
            }
        }
        return completions;
    }

    private String serverPlatform() {
        if (hasClass("io.papermc.paper.threadedregions.RegionizedServer")
                || containsIgnoreCase(Bukkit.getVersion(), "folia")
                || containsIgnoreCase(Bukkit.getServer().getName(), "folia")) {
            return "folia";
        }

        if (hasClass("io.papermc.paper.configuration.Configuration")
                || hasClass("com.destroystokyo.paper.PaperConfig")
                || containsIgnoreCase(Bukkit.getServer().getName(), "paper")
                || containsIgnoreCase(Bukkit.getVersion(), "paper")) {
            return "paper";
        }

        if (hasClass("org.purpurmc.purpur.PurpurConfig")
                || containsIgnoreCase(Bukkit.getVersion(), "purpur")) return "purpur";


        if (hasClass("org.spigotmc.SpigotConfig")
                || containsIgnoreCase(Bukkit.getServer().getName(), "spigot")
                || containsIgnoreCase(Bukkit.getVersion(), "spigot")) {
            return "spigot";
        }

        return "bukkit";
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null
                && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
