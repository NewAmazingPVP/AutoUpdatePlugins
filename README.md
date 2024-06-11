# AutoUpdatePlugins

AutoUpdatePlugins is a versatile plugin for Spigot, Bungeecord, and Velocity that automates the updating process for any plugin you specify!

[Spigot Link](https://www.spigotmc.org/resources/autoupdateplugins.109683/)

## Features

- **Compatibility**: Supports Minecraft versions 1.8 and higher for Spigot, Velocity, and Bungeecord, including the latest versions.
- **Automatic Downloads**: Fetches the latest releases from Spigotmc.org, GitHub, Jenkins, dev.bukkit.org, modrinth.com, and hangar.papermc.io based on user-provided links. Also includes multi-artifact/jar support by specifying [num] at end of link for GitHub and jenkins.
- **Configurable Update Interval**: Customize the update interval in the `config.yml` file.
- **Plugin List Management**: Define which plugins to update and their download links in the `list.yml` file, including specifying the plugin jar file names.
- **Manual Update Command**: Use the `/update` command to manually trigger updates (requires `autoupdateplugins.update` permission).

## Planned Features

- Have suggestions or feature requests? Create a new issue in the project's [GitHub repository](https://github.com/NewAmazingPVP/AutoUpdatePlugins).

## Installation

1. **Preparation**: Ensure no plugins are pre-installed, as they will be installed automatically.
2. **Initial Setup**: Start the server, then restart it after the first run to enable supported plugins.
3. **Configuration**: 
   - Edit `list.yml` to specify the plugins you want to update:
     ```yaml
     ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
     Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
     ```
   - Edit `config.yml` for update intervals and other settings:
     ```yaml
     updates:
       interval: 120
       bootTime: 50
     ```

## Detailed Configuration

### list.yml

Specify plugins to update in this format:
```yaml
{pluginJarfileSaveName}: {link.to.plugin}
```

**Examples**:
```yaml
ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]" # For multi-artifact plugins, specify the artifact number within brackets.
#
# The plugin takes in a Spigot/Jenkins/Bukkit/Github/Modrinth/Hangar resource link or a direct link that automatically redirects and downloads the latest release.
# Make sure if it's a Spigot resource link that the resource can be directly downloaded on Spigot itself and not on an external website
# For Bukkit, Jenkins, Github, Modrinth, Hangar links, make sure they just point to the project (not the releases just the main project website)
# For example:
#   - The first example downloads the plugin from https://www.spigotmc.org/resources/viaversion.19254/ and names it ViaVersion (the .jar extension will be added automatically when downloaded)
#   - The second example downloads the Geyser Spigot version and saves it as Geyser
#   - The third example downloads the EssentialsXChat artifact from the EssentialsX project on GitHub. It specifies the artifact number (jar in release bundle) as 3 using the format '[3]' at end.

```

### config.yml

Configure the update intervals and startup behavior:
```yaml
# Plugin Updates Configuration
updates:
  interval: 120  # Time between plugin updates in minutes
  bootTime: 50   # Delay in seconds after server startup before updating plugins
```

### Template list.yml

Here is a template for `list.yml`:
```yaml
AuthMeReloaded: https://ci.codemc.io/job/AuthMe/job/AuthMeReloaded/[4]
BlueSlimeCore: https://www.spigotmc.org/resources/blueslimecore.83189/
Chunky: https://www.spigotmc.org/resources/chunky.81534/
ChunkyBorder: https://www.spigotmc.org/resources/chunkyborder.84278/
DiscordSRV: https://get.discordsrv.com/
Dynmap: https://dev.bukkit.org/projects/dynmap
EasyPrefix: https://www.spigotmc.org/resources/easyprefix-gui-custom-prefixes-sql-support.44580/
EssentialsX: https://github.com/EssentialsX/Essentials
EssentialsXChat: https://github.com/EssentialsX/Essentials[3]
FAWE: https://ci.athion.net/job/FastAsyncWorldEdit/
Floodgate: https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot
Geyser-spigot: https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot
InventoryRollbackPlus: https://www.spigotmc.org/resources/inventory-rollback-plus.85811/
lssmp: https://www.spigotmc.org/resources/lifesteal-smp-plugin.94387/
LuckPerms: https://www.spigotmc.org/resources/luckperms.28140/
PlaceholderAPI: https://www.spigotmc.org/resources/placeholderapi.6245/
PlayerStats: https://www.spigotmc.org/resources/playerstats.102347/
ProtocolLib: https://ci.dmulloy2.net/job/ProtocolLib/
SkinsRestorer: https://ci.codemc.io/job/SkinsRestorer/job/SkinsRestorerX-DEV/
TAB: https://github.com/NEZNAMY/TAB
ViewDistanceTweaks: https://www.spigotmc.org/resources/view-distance-tweaks.75164/
Voicemod: https://modrinth.com/plugin/simple-voice-chat/
ViaVersion-Dev: https://ci.viaversion.com/job/ViaVersion-DEV/
ViaBackwards: https://hangar.papermc.io/ViaVersion/ViaBackwards
Worldedit: https://dev.bukkit.org/projects/worldedit
Worldguard: https://dev.bukkit.org/projects/worldguard
```

## License

AutoUpdatePlugins is released under the MIT License. See the [LICENSE file](https://github.com/NewAmazingPVP/AutoUpdatePlugins/blob/main/LICENSE) for more information.

## Support

For support, join our [Discord](https://discord.gg/u3u45vaV6G).

## Metrics

- **BStats**: Track usage and other metrics with BStats.
- **Compatibility**: Works with Bukkit, Bungeecord, and Velocity.
