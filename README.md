<div align="center">

# AutoUpdatePlugins

Keep your server’s plugins always fresh — safely, automatically, and across every ecosystem.

[Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/) · Paper/Folia · Velocity · Bungeecord · 1.8 → Latest

</div>

## Highlights

- Safe updates: temp download, integrity checks, checksum verification, atomic replace
- Smart sources: GitHub (Releases & Actions), Jenkins, SpigotMC (via Spiget), dev.bukkit, Modrinth, Hangar, BusyBiscuit, blob.build, Guizhanss builds v2, MineBBS, CurseForge, generic pages
- Folia/Paper aware: async scheduler on Folia/Paper; Bukkit fallback for 1.8+
- GitHub source build: auto-compiles from repo when needed (zip-only releases or branch significantly newer) or on demand with `?autobuild=true`
- Powerful selectors: pick assets with `?get=<regex>`, allow pre-releases with `?prerelease=true`, force build with `?autobuild=true`
- Config regeneration: new options appear automatically with helpful comments
- Customizable: headers, proxies, paths, behavior toggles

## Requirements

- Java 8+ (builds against Java 8)
- For source builds: network access for Gradle/Maven wrappers or system `gradle`/`mvn`

## Installation

1) Drop the jar into your `plugins/` folder and start the server once.

2) Configure `plugins/AutoUpdatePlugins/config.yml` and `list.yml`.

3) Restart or run `/update` or `/aup update` to trigger updates.

The plugin will update your plugins at the configured interval (Folia/Paper async or Bukkit async).

## Quick Start

list.yml (simple examples):

```yaml
ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
```

config.yml (sane defaults):

```yaml
updates:
  interval: 120      # minutes
  bootTime: 50       # seconds
  key: ""           # optional GitHub token for Actions/auth

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

paths:
  tempPath: ''       # optional
  updatePath: ''     # optional
  filePath: ''       # optional
```

All of these keys regenerate automatically with comments preserved. Change only what you need.

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
- `/aup add <name> <link>` — append to list.yml
- `/aup remove <name>` — remove from list.yml
- `/aup list [page]` — view configured plugins
- `/aup enable|disable <name>` — toggle entries via comments in list.yml

Permissions:

- `autoupdateplugins.update` — use `/update`
- `autoupdateplugins.manage` — use `/aup ...`

## Safety & Integrity

- Downloads into `.download.tmp`, stages jar to `.temp`, validates, then atomically replaces
- Validates `Content-Length`, zip readability, jar readability
- Verifies checksums via headers (`X-Checksum-*`, or hex `ETag`)
- Skips replacing when MD5 matches the current file (configurable)

## Tips

- Provide a GitHub token in `updates.key` to access Actions artifacts and higher rate limits
- Use `http.headers` to add vendor-specific headers if a source needs them
- Configure `proxy` if your network requires it

## Build From Source (local)

```bash
mvn -DskipTests package
# Jar will be under target/*.jar
```

## CI: GitHub Actions

On every push and PR, the project is built and the fat jar is uploaded as a workflow artifact. On tagging a commit (e.g. `v1.2.3`), a GitHub Release is created and the jar is attached.

See `.github/workflows/build.yml` for details.

## License

MIT — see LICENSE for details.

# For support, join our \[Discord](https://discord.gg/u3u45vaV6G).

# 

# \## Metrics

# 

# \- \*\*BStats\*\*: Track usage and other metrics with BStats.

# \- \*\*Compatibility\*\*: Works with Bukkit, Bungeecord, and Velocity.

