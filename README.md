# AutoUpdatePlugins

<div align="center">

# AutoUpdatePlugins v12.0.0

Keep your server’s plugins up-to-date — automatically, safely, and across platforms.

[Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/) · Paper/Folia · Velocity · BungeeCord · 1.8 → Latest

</div>

## Features

*   **Multiple Download Sources:** AutoUpdatePlugins supports a wide range of download sources, including:
    *   GitHub (Releases & Actions)
    *   Jenkins
    *   SpigotMC (Spiget)
    *   dev.bukkit
    *   Modrinth
    *   Hangar
    *   BusyBiscuit
    *   blob.build
    *   Guizhanss v2
    *   MineBBS
    *   CurseForge
    *   Generic web pages with direct `.jar` links
*   **Smart Selection:** You can use various query parameters to select the exact file you want:
    *   `?get=<regex>`: Match by filename using a regular expression.
    *   `[N]`: Pick the Nth jar asset.
    *   `?prerelease=true`: Allow pre-releases.
    *   `?autobuild=true`: Force a source build.
*   **Regenerating Config:** The plugin automatically adds new configuration options to your `config.yml` file without overwriting your existing settings or comments.
*   **Performance Optimized:** AutoUpdatePlugins is designed to be fast and efficient:
    *   **Async Downloads:** All downloads are performed asynchronously to avoid blocking the main server thread.
    *   **Java 11+ HTTP/2 Client:** If you are using Java 11 or newer, the plugin will use the modern, non-blocking HTTP/2 client for even faster downloads.
    *   **Java 21+ Virtual Threads:** If you are using Java 21 or newer, the plugin will use virtual threads to handle downloads, which is even more efficient than the traditional thread pool approach.
    *   **Connection Pooling:** The plugin uses a connection pool to reuse existing connections, which reduces the overhead of creating new connections for each download.
    *   **Parallel Downloads:** You can configure the plugin to download multiple plugins in parallel, which can significantly speed up the update process.
    *   **Retry with Backoff:** If a download fails, the plugin will automatically retry it with an exponential backoff delay to avoid spamming the download server.
*   **Scheduling:** You can schedule the update checks to run at a specific interval or use a cron expression for more advanced scheduling.
*   **Cross-Platform:** The plugin works on Spigot, Paper, Folia, Velocity, and BungeeCord.

## Supported Platforms

*   Spigot
*   Paper
*   Folia
*   Velocity
*   BungeeCord

## Requirements

*   Java 8 or newer.
*   For source builds, you need network access for the Gradle/Maven wrappers or system `gradle`/`mvn`.

## Installation

1.  Download the latest version of the plugin from the [Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/) page.
2.  Drop the downloaded `.jar` file into your server's `plugins/` folder.
3.  Start your server to generate the default configuration files.
4.  Configure the plugin by editing the `plugins/AutoUpdatePlugins/config.yml` and `plugins/AutoUpdatePlugins/list.yml` files.
5.  Restart the server or run the `/update` or `/aup update` command to trigger the first update check.

## Configuration

### `config.yml`

The `config.yml` file contains the main configuration for the plugin.

```yaml
# Global update settings
updates:
  # Time in minutes between update checks.
  interval: 120
  # Time in seconds to delay the first update check after the server starts.
  bootTime: 50

  # Schedule settings (overrides interval and bootTime when set)
  schedule:
    # Cron expression for scheduling update checks. Leave blank to disable.
    cron: ""
    # Timezone for the cron expression (e.g., "America/New_York").
    timezone: "UTC"

  # Optional GitHub token (PAT) to access Actions artifacts and avoid rate limits.
  key:

# HTTP client settings
http:
  # Custom User-Agent for HTTP requests.
  userAgent: ""
  # A list of custom headers to add to all HTTP requests.
  headers: []
  # A list of User-Agents to rotate through for each request.
  userAgents:
    - { ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36" }
    - { ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15" }
    - { ua: "Mozilla/5.0 (X11; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0" }

# Proxy settings
proxy:
  # Proxy type: DIRECT, HTTP, or SOCKS.
  type: DIRECT
  # Proxy host.
  host: "127.0.0.1"
  # Proxy port.
  port: 7890

# Behavior settings
behavior:
  # Whether to check if the downloaded file is a valid ZIP file.
  zipFileCheck: true
  # Whether to ignore downloading a new file if it has the same name and size as the existing file.
  ignoreDuplicates: true
  # Whether to allow pre-releases to be downloaded.
  allowPreRelease: false
  # Auto-compile settings for GitHub repositories.
  autoCompile:
    # Whether to enable auto-compiling.
    enable: true
    # Whether to auto-compile when no JAR asset is found in the latest release.
    whenNoJarAsset: true
    # The number of months to check for newer commits on the default branch.
    branchNewerMonths: 4
  # Whether to enable debug mode.
  debug: false

# Path settings
paths:
  # The temporary path for downloading files. Leave blank to use the default path.
  tempPath: ''
  # The path to the update folder. Leave blank to use the default path.
  updatePath: ''
  # The path to the plugins folder. Leave blank to use the default path.
  filePath: ''

# Performance settings
performance:
  # The maximum number of parallel downloads.
  maxParallel: 4
  # The connection timeout in milliseconds.
  connectTimeoutMs: 10000
  # The read timeout in milliseconds.
  readTimeoutMs: 30000
  # The timeout for each download in seconds. 0 to disable.
  perDownloadTimeoutSec: 0
  # The maximum number of retries for a failed download.
  maxRetries: 4
  # The base backoff delay in milliseconds.
  backoffBaseMs: 500
  # The maximum backoff delay in milliseconds.
  backoffMaxMs: 5000
  # The maximum number of connections per host.
  maxPerHost: 3
```

### `list.yml`

The `list.yml` file contains a list of all the plugins that you want to update.

```yaml
# A list of plugins to update.
# Format: <plugin-name>: <download-url>

ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
```

## Commands

*   `/update`: Trigger an update run.
*   `/aup download [names...]`: Update all or specific plugins.
*   `/aup update [names...]`: Alias of download.
*   `/aup stop`: Request to stop the current updating process.
*   `/aup reload`: Reload the plugin configuration.
*   `/aup add <name> <link>`: Add a new plugin to the `list.yml` file.
*   `/aup remove <name>`: Remove a plugin from the `list.yml` file.
*   `/aup list [page]`: View the list of configured plugins.
*   `/aup enable|disable <name>`: Enable or disable a plugin by commenting it out in the `list.yml` file.

### Permissions

*   `autoupdateplugins.update`: Allows a player to use the `/update` command.
*   `autoupdateplugins.manage`: Allows a player to use the `/aup` commands.

## How it Works

AutoUpdatePlugins works by periodically checking for new versions of the plugins listed in the `list.yml` file. When a new version is found, the plugin downloads it to a temporary file, validates it, and then replaces the old plugin file with the new one. The replacement is done atomically to ensure that the server is not left in an inconsistent state.

The plugin uses a variety of techniques to download plugins from different sources. For GitHub, it uses the GitHub API to get the latest release and download the assets. For SpigotMC, it uses the Spiget API. For other sources, it uses a combination of HTML parsing and direct downloads.

## Building from Source

To build the plugin from the source code, you need to have [Maven](https://maven.apache.org/) installed. Then, you can run the following command in the root directory of the project:

```bash
mvn -DskipTests package
```

This will create a `.jar` file in the `target/` directory.

## Bug Reports and Feature Requests

If you find a bug or have a feature request, please open an issue on the [GitHub repository](https://github.com/NewAmazingPVP/AutoUpdatePlugins/issues).

## License

AutoUpdatePlugins is licensed under the [MIT License](LICENSE).