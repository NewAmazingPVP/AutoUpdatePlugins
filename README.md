<div align="center">

# AutoUpdatePlugins v12.0.0

Keep your server’s plugins up-to-date — automatically, safely, and across platforms.

[Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/) · Paper/Folia · Velocity · BungeeCord · 1.8 → Latest

</div>

## Highlights

- Multiple sources: GitHub (Releases & Actions), Jenkins, SpigotMC (Spiget), dev.bukkit, Modrinth, Hangar, BusyBiscuit, blob.build, Guizhanss v2, MineBBS, CurseForge, generic pages
- Smart selection: `?get=<regex>`, pick by index `[N]`, `?prerelease=true`, force source build `?autobuild=true`
- Regenerating config: new options appear automatically and keep your comments
- Async + fast: Folia/Paper aware, parallel downloads, retry/backoff
- Scheduling: interval-based or cron (experimental) with timezone

## Requirements

- Java 8+ (builds against Java 8)
- For source builds: network access for Gradle/Maven wrappers or system `gradle`/`mvn`

## Installation

1) Drop the jar into your `plugins/` folder and start the server once.

2) Configure `plugins/AutoUpdatePlugins/config.yml` and `list.yml`.

3) Restart or run `/update` or `/aup update` to trigger updates.

The plugin runs at the configured schedule (interval or cron). Updates are applied on restart if your platform uses an `update/` folder.

## Quick Start

list.yml (simple examples):

```yaml
ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
```

Default config (excerpt):

```yaml
updates:
  interval: 120      # minutes between update runs
  bootTime: 50       # seconds to delay after startup

  # Schedule (experimental): overrides interval + bootTime when set
  schedule:
    cron: ""         # Cron expression (UNIX 5-field). Leave blank to disable.
    timezone: "UTC"  # Timezone for cron, e.g. "America/New_York"

  # Optional GitHub token (PAT) to access Actions artifacts and avoid rate limits
  key:

http:
  userAgent: ""
  headers: []
  userAgents:
    - { ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36" }
    - { ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15" }
    - { ua: "Mozilla/5.0 (X11; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0" }

proxy:
  type: DIRECT       # DIRECT | HTTP | SOCKS
  host: "127.0.0.1"
  port: 7890

behavior:
  zipFileCheck: true
  ignoreDuplicates: true
  allowPreRelease: false
  autoCompile:
    enable: true
    whenNoJarAsset: true
    branchNewerMonths: 4
  debug: false

paths:
  tempPath: ''       # optional
  updatePath: ''     # optional
  filePath: ''       # optional

performance:
  maxParallel: 4
  connectTimeoutMs: 10000
  readTimeoutMs: 30000
  perDownloadTimeoutSec: 0
  maxRetries: 4
  backoffBaseMs: 500
  backoffMaxMs: 5000
```

Velocity, Spigot/Paper, and Bungee/Waterfall all use `config.yml` and `list.yml`. Config regeneration preserves your comments and values.

## Supported Sources

- GitHub Releases and Actions (with optional `updates.key` token)
- Jenkins (multi-artifact supported)
- SpigotMC (via Spiget), dev.bukkit.org
- Modrinth, Hangar
- BusyBiscuit builds, blob.build, Guizhanss builds v2
- MineBBS, CurseForge (best-effort HTML parsing)
- Generic pages with direct `.jar` links

## Selecting Assets & Forcing Behavior

- Index (GitHub/Jenkins): append `[N]` to pick the Nth jar asset
- Regex (GitHub/Jenkins/Actions/Modrinth): append `?get=<regex>` to match by filename
- Pre-releases (GitHub): set global `behavior.allowPreRelease: true` or per-link `?prerelease=true`
- Force source build (GitHub): append `?autobuild=true` to a GitHub repo link

Examples:

```yaml
EssentialsXChat: "https://github.com/EssentialsX/Essentials?get=EssentialsXChat-([0-9.]+)\.jar&prerelease=true"
ViaVersion: "https://ci.viaversion.com/job/ViaVersion-DEV/?get=ViaVersion-.*\.jar"
CoreProtect: "https://modrinth.com/plugin/coreprotect/?get=CoreProtect-.*\.jar"
UnlimitedNameTags: "https://github.com/alexdev03/UnlimitedNametags?autobuild=true"
```

## GitHub Source Build (auto-compile)

When enabled, the plugin will build from source if:

- The latest (pre)release has no `.jar` assets (zip-only), or
- The default branch has commits newer than the latest (pre)release by `behavior.autoCompile.branchNewerMonths` months.

Builds use project wrappers (`gradlew`/`mvnw`) when present, falling back to system `gradle`/`mvn`.

To force a source build for a repository link, append `?autobuild=true` to the GitHub URL in `list.yml`.

## Commands

- `/update` — trigger an update run
- `/aup download [names...]` — update all or specific plugins
- `/aup update [names...]` — alias of download
- `/aup stop` — request stop of the current updating process
- `/aup reload` — reload the plugin configuration (all platforms)
- `/aup add <name> <link>` — append to list.yml
- `/aup remove <name>` — remove from list.yml
- `/aup list [page]` — view configured plugins
- `/aup enable|disable <name>` — toggle entries via comments in list.yml

Permissions:

- `autoupdateplugins.update` — use `/update`
- `autoupdateplugins.manage` — use `/aup ...`

## How it Updates

- Downloads to a temp file, validates, then replaces atomically
- Retries on transient errors with exponential backoff
- Skips replacing if the new file matches the existing (configurable)


## Tips

- Provide a GitHub token in `updates.key` to access Actions artifacts and higher rate limits
- Use `http.headers` to add vendor-specific headers if a source needs them
- Configure `proxy` if your network requires it (default host is `proxy.example.com`, port `8080`)

## Build From Source

```bash
mvn -DskipTests package
```

## License

MIT — see LICENSE for details.

## Configuration Reference (concise)

- updates.interval / updates.bootTime: interval schedule (overridden by cron)
- updates.schedule.cron / timezone: cron expression and timezone
- updates.key: GitHub token for Actions and higher rate limits (optional)
- http.*: userAgent override, extra headers, and rotating userAgents list
- proxy.*: DIRECT | HTTP | SOCKS and host/port
- behavior.*: integrity checks, duplicate-skip, pre-releases, auto-compile, debug
- paths.*: optional custom directories
- performance.*: parallelism, timeouts, retries, and backoff

For support, join our Discord: https://discord.gg/u3u45vaV6G
