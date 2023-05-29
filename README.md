# AutoUpdatePlugins
AutoUpdatePlugins is a Spigot/Bungeecord/Velocity plugin that automates the updating process for any plugin you want!

Spigot link: https://www.spigotmc.org/resources/autoupdateplugins.109683/

# Features
- Compatible with Minecraft versions 1.8 and higher for Spigot, Velocity, and Bungeecord, including the latest versions.

- Automatically downloads the latest release of Spigotmc.org resource links, Github plugin links, and Jenkins links provided by the user.

- Configurable update interval in the config.yml file.

- list.yml file for specifying which plugins to download and their respective links. It also allows configuring the plugin jar file name.

# Planned Features
- If you have any suggestions or feature requests, please create a new issue in the project's GitHub repository.

# Installation
Ensure no plugins are pre-installed, as they'll install automatically. 
Start the server, and restart it after the first run to enable supported plugins. 
AutoUpdatePlugins will update specified plugins in list.yml at the interval set in config.yml. 
Configure list.yml to specify plugins to update:
# Plugins List Configuration:
Go to the list.yml file created in this same directory and put the plugin information that you want to periodically update in this format:
 - {pluginJarfileSaveName}: {link.to.plugin}

Examples:

  ViaVersion: https://www.spigotmc.org/resources/viaversion.19254/
  
  
  Geyser: https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot

The plugin takes in a Spigot resource link or a direct link that automatically redirects and downloads the latest release.
For example:
  - The first example downloads the plugin from https://www.spigotmc.org/resources/viaversion.19254/ and names it ViaVersion (the .jar extension will be added automatically when downloaded)
  - The second example downloads the Geyser Spigot version and saves it as Geyser

# License
AutoUpdatePlugins is released under the MIT License. See the LICENSE file for more information.
