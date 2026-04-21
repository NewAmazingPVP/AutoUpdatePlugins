package velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import common.ConfigManager;
import common.ListEntryLoader;
import common.PluginUpdater;
import common.UpdateOptions;
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
    private final ConfigManager cfgMgr;
    private final Runnable reloadAction;
    private final Runnable updateAllAction;

    public AupCommand(PluginUpdater pluginUpdater, File listFile, ConfigManager cfgMgr, Runnable reloadAction, Runnable updateAllAction) {
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.cfgMgr = cfgMgr;
        this.reloadAction = reloadAction;
        this.updateAllAction = updateAllAction;
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
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "download":
            case "update":
                install(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "check":
                check(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "pending":
                showPending(source);
                break;
            case "add":
                if (args.length >= 3) {
                    addPlugin(source, args[1], joinArgs(args, 2));
                } else {
                    source.sendMessage(error("Usage: /aup add <identifier> <link>"));
                }
                break;
            case "stop":
                stopUpdating(source);
                break;
            case "reload":
                reloadConfig(source);
                break;
            case "remove":
                if (args.length >= 2) {
                    removePlugins(source, args[1]);
                } else {
                    source.sendMessage(error("Usage: /aup remove <identifier|group>"));
                }
                break;
            case "list":
                int page = 1;
                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                listPlugins(source, page);
                break;
            case "enable":
                if (args.length >= 2) {
                    setEnabled(source, args[1], true);
                } else {
                    source.sendMessage(error("Usage: /aup enable <identifier|group>"));
                }
                break;
            case "disable":
                if (args.length >= 2) {
                    setEnabled(source, args[1], false);
                } else {
                    source.sendMessage(error("Usage: /aup disable <identifier|group>"));
                }
                break;
            case "debug":
                toggleDebug(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                sendHelp(source);
                break;
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(info("AutoUpdatePlugins Commands:"));
        source.sendMessage(info("/aup update [plugin|group...]"));
        source.sendMessage(info("/aup check [plugin|group...]"));
        source.sendMessage(info("/aup pending"));
        source.sendMessage(info("/aup stop"));
        source.sendMessage(info("/aup reload"));
        source.sendMessage(info("/aup debug <on|off|toggle|status>"));
        source.sendMessage(info("/aup add <identifier> <link>"));
        source.sendMessage(info("/aup remove <identifier|group>"));
        source.sendMessage(info("/aup list [page]"));
        source.sendMessage(info("/aup enable <identifier|group>"));
        source.sendMessage(info("/aup disable <identifier|group>"));
        source.sendMessage(Component.text("Use group:<name> to target a group explicitly.").color(NamedTextColor.GRAY));
    }

    private void stopUpdating(CommandSource source) {
        boolean stopped = pluginUpdater.stopUpdates();
        source.sendMessage(Component.text(stopped ? "Stop requested. In-flight downloads may complete." : "No update is currently running.")
                .color(stopped ? NamedTextColor.YELLOW : NamedTextColor.RED));
    }

    private void reloadConfig(CommandSource source) {
        try {
            if (reloadAction != null) reloadAction.run();
        } catch (Throwable ignored) {
        }
        source.sendMessage(success("AutoUpdatePlugins configuration reloaded."));
    }

    private void toggleDebug(CommandSource sender, String[] args) {
        boolean current = UpdateOptions.debug;
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text("Debug is currently ").color(NamedTextColor.AQUA)
                    .append(Component.text(current ? "ON" : "OFF").color(current ? NamedTextColor.GREEN : NamedTextColor.RED)));
            return;
        }
        boolean next = current;
        if ("on".equalsIgnoreCase(args[0])) next = true;
        else if ("off".equalsIgnoreCase(args[0])) next = false;
        else if ("toggle".equalsIgnoreCase(args[0])) next = !current;
        else {
            sender.sendMessage(error("Usage: /aup debug <on|off|toggle|status>"));
            return;
        }
        UpdateOptions.debug = next;
        cfgMgr.setOption("behavior.debug", next);
        sender.sendMessage(Component.text("Debug is now ").color(NamedTextColor.AQUA)
                .append(Component.text(next ? "ON" : "OFF").color(next ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    private void install(CommandSource source, String[] selectors) {
        if (pluginUpdater.isUpdating()) {
            source.sendMessage(error("An update is already in progress. Please wait."));
            return;
        }
        if (selectors.length == 0) {
            if (updateAllAction != null) {
                updateAllAction.run();
            } else {
                pluginUpdater.updateList(listFile, "velocity", cfgMgr.getString("updates.key"));
            }
            source.sendMessage(success("Installing all plugin updates..."));
            return;
        }

        Map<String, PluginEntry> resolved = resolveEntries(source, selectors);
        for (PluginEntry entry : resolved.values()) {
            pluginUpdater.updatePlugin("velocity", cfgMgr.getString("updates.key"), entry.name, entry.link);
        }
    }

    private void check(CommandSource source, String[] selectors) {
        if (pluginUpdater.isUpdating()) {
            source.sendMessage(error("An update is already in progress. Please wait."));
            return;
        }
        if (selectors.length == 0) {
            pluginUpdater.checkList(listFile, "velocity", cfgMgr.getString("updates.key"));
            source.sendMessage(info("Update check started."));
            return;
        }

        Map<String, PluginEntry> resolved = resolveEntries(source, selectors);
        for (PluginEntry entry : resolved.values()) {
            pluginUpdater.checkPlugin("velocity", cfgMgr.getString("updates.key"), entry.name, entry.link);
        }
        if (!resolved.isEmpty()) {
            source.sendMessage(info("Checking " + resolved.size() + " plugin(s) for updates."));
        }
    }

    private void showPending(CommandSource source) {
        Map<String, PluginUpdater.PendingUpdate> pending = pluginUpdater.getPendingUpdates();
        if (pending.isEmpty()) {
            source.sendMessage(Component.text("No pending updates.").color(NamedTextColor.YELLOW));
            return;
        }
        source.sendMessage(info("Pending updates:"));
        for (PluginUpdater.PendingUpdate update : pending.values()) {
            source.sendMessage(Component.text(update.pluginName + " -> " + update.targetPath).color(NamedTextColor.YELLOW));
        }
    }

    private void addPlugin(CommandSource source, String name, String link) {
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            lines.add(name + ": " + link);
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
            source.sendMessage(success("Added " + name));
        } catch (IOException e) {
            source.sendMessage(error("Failed to add plugin: " + e.getMessage()));
        }
    }

    private void removePlugins(CommandSource source, String selector) {
        Map<String, PluginEntry> resolved = resolveEntries(source, new String[]{selector});
        if (resolved.isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>(resolved.keySet());
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
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
            source.sendMessage(changed ? success("Removed " + names.size() + " plugin(s).") : error("Nothing matched in list.yml"));
        } catch (IOException e) {
            source.sendMessage(error("Failed to remove plugin: " + e.getMessage()));
        }
    }

    private void listPlugins(CommandSource source, int page) {
        List<PluginEntry> entries = new ArrayList<>(loadEntries().values());
        if (entries.isEmpty()) {
            source.sendMessage(Component.text("list.yml is empty.").color(NamedTextColor.YELLOW));
            return;
        }
        int perPage = 8;
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        if (page < 1) page = 1;
        if (page > pages) page = pages;
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        source.sendMessage(info("Plugins (page " + page + "/" + pages + "):"));
        for (int i = start; i < end; i++) {
            PluginEntry entry = entries.get(i);
            String label = entry.group != null && !entry.group.isEmpty()
                    ? "[" + entry.group + "] " + entry.name
                    : entry.name;
            source.sendMessage(Component.text(label + " -> " + entry.link).color(entry.enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
    }

    private void setEnabled(CommandSource source, String selector, boolean enable) {
        Map<String, PluginEntry> resolved = resolveEntries(source, new String[]{selector});
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
            source.sendMessage(changed ? success((enable ? "Enabled " : "Disabled ") + names.size() + " plugin(s).")
                    : Component.text("No changes were needed.").color(NamedTextColor.YELLOW));
        } catch (IOException e) {
            source.sendMessage(error("Failed to modify plugin: " + e.getMessage()));
        }
    }

    private Map<String, PluginEntry> resolveEntries(CommandSource source, String[] selectors) {
        ListEntryLoader.LoadedList loadedList = ListEntryLoader.loadList(listFile);
        Map<String, PluginEntry> entries = toPluginEntries(loadedList);
        LinkedHashMap<String, PluginEntry> resolved = new LinkedHashMap<>();
        for (String selector : selectors) {
            if (!addSelectorMatches(resolved, loadedList, entries, selector)) {
                source.sendMessage(error("Target " + selector + " not found in list.yml"));
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

    @Override
    public List<String> suggest(Invocation invocation) {
        List<String> completions = new ArrayList<>();
        CommandSource source = invocation.source();
        if (!source.hasPermission("autoupdateplugins.manage")) {
            return completions;
        }
        String[] args = invocation.arguments();
        String[] subs = {"download", "update", "check", "pending", "stop", "reload", "add", "remove", "list", "enable", "disable", "debug"};
        if (args.length == 0) {
            completions.addAll(Arrays.asList(subs));
            return completions;
        }
        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            for (String sub : subs) {
                if (sub.startsWith(current)) completions.add(sub);
            }
            return completions;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (Arrays.asList("download", "update", "check", "remove", "enable", "disable").contains(sub)) {
                completions.addAll(selectorSuggestions(args[1]));
            } else if ("list".equals(sub)) {
                int pages = Math.max(1, (int) Math.ceil(loadEntries().size() / 8.0));
                for (int i = 1; i <= pages; i++) {
                    String page = Integer.toString(i);
                    if (page.startsWith(args[1])) completions.add(page);
                }
            }
        } else if (args.length > 2 && Arrays.asList("download", "update", "check").contains(sub)) {
            completions.addAll(selectorSuggestions(args[args.length - 1]));
        }
        return completions;
    }

    private Component info(String message) {
        return Component.text(message).color(NamedTextColor.AQUA);
    }

    private Component success(String message) {
        return Component.text(message).color(NamedTextColor.GREEN);
    }

    private Component error(String message) {
        return Component.text(message).color(NamedTextColor.RED);
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
}
