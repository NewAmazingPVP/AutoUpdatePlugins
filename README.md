# AutoUpdatePlugins

<div align="center">

# **AutoUpdatePlugins v12.0.0**

*Keep your server’s plugins up-to-date — automatically, safely, and across platforms.*

[![SpigotMC](https://img.shields.io/badge/SpigotMC-Resource-orange)](https://www.spigotmc.org/resources/autoupdateplugins.109683/)
![Platforms](https://img.shields.io/badge/Platforms-Spigot%20%7C%20Paper%20%7C%20Folia%20%7C%20Velocity%20%7C%20BungeeCord-5A67D8)
![MC](https://img.shields.io/badge/Minecraft-1.8%E2%86%92Latest-2EA043)
![Java](https://img.shields.io/badge/Java-8%2B%20\(11%2B%20HTTP%2F2,%2021%2B%20Virtual%20Threads\)-1F6FEB)
![License](https://img.shields.io/badge/License-MIT-0E8A16)

</div>

> **TL;DR**
> Drop in the jar ➜ list the plugins you want ➜ it finds, downloads, validates, and stages updates from a ton of sources — on a schedule, in parallel, without blocking your main thread.

---

## Table of Contents

* [Highlights](#highlights)
* [Supported Sources & Platforms](#supported-sources--platforms)
* [Requirements](#requirements)
* [Installation](#installation)
* [Quick Start](#quick-start)
* [Configuration](#configuration)

    * [`config.yml`](#configyml)
    * [`list.yml`](#listyml)
    * [Scheduling Cheat Sheet](#scheduling-cheat-sheet)
    * [Performance Tuning Guide](#performance-tuning-guide)
* [Commands & Permissions](#commands--permissions)
* [How It Works (Under the Hood)](#how-it-works-under-the-hood)
* [Examples for Every Source](#examples-for-every-source)
* [Troubleshooting & FAQ](#troubleshooting--faq)
* [Building from Source](#building-from-source)
* [Security Notes](#security-notes)
* [Contributing, Issues & Support](#contributing-issues--support)
* [License](#license)

---

## Highlights

* **One list, many sources.** Pull updates from GitHub Releases/Actions, Jenkins, SpigotMC (Spiget), dev.bukkit, Modrinth, Hangar, BusyBiscuit, blob.build, Guizhanss v2, MineBBS, CurseForge, or any page with a direct `.jar` link.
* **Smart file selection.** Use `?get=<regex>`, `[N]` (pick the N-th asset), `?prerelease=true`, `?autobuild=true` (force source build) to precisely target the artifact you want.
* **Zero-friction config evolution.** New options are **auto-added** to `config.yml` without clobbering your comments or existing values.
* **Performance built-in.**

    * Fully **async** downloads; no main-thread stalls.
    * **Java 11+ HTTP/2** client for modern, non-blocking transfers.
    * **Java 21+ virtual threads** for ultra-lightweight concurrency.
    * **Connection pooling**, **parallel downloads**, and **retry with exponential backoff**.
* **Flexible scheduling.** Interval + boot delay *or* cron with timezone support.
* **Cross-platform.** Works on **Spigot**, **Paper**, **Folia**, **Velocity**, **BungeeCord**.
* **Safe updates.** Download ➜ validate ➜ atomic replace, staged through temp/update paths.

---

## Supported Sources & Platforms

### Download Sources

| Source                | Release/Build Discovery | Notes / Selectors Supported                                |
| --------------------- | ----------------------- | ---------------------------------------------------------- |
| **GitHub**            | Releases & Actions      | `[N]`, `?get=regex`, `?prerelease=true`, `?autobuild=true` |
| **Jenkins**           | Latest build artifacts  | `?get=regex`                                               |
| **SpigotMC (Spiget)** | Resource page URL       | Auto-resolves latest                                       |
| **dev.bukkit**        | Project page            | Auto-resolves latest                                       |
| **Modrinth**          | Project/version URL     | `?get=regex`                                               |
| **Hangar**            | Project/releases        | `?get=regex`                                               |
| **BusyBiscuit**       | Project index           | Auto-resolves                                              |
| **blob.build**        | Build artifacts         | `?get=regex`                                               |
| **Guizhanss v2**      | Project index           | Auto-resolves                                              |
| **MineBBS**           | Resource page           | Auto-resolves                                              |
| **CurseForge**        | Project/files           | `?get=regex`                                               |
| **Generic**           | Direct `.jar` link      | Exact file URL                                             |

> **Tip:** You can pass just the **project root URL** for most sources (e.g., a GitHub repo or Spigot resource page) and let AutoUpdatePlugins choose the latest artifact. Add selectors for precision.

### Server Platforms

* **Spigot**, **Paper**, **Folia**
* **Velocity**, **BungeeCord**

### Minecraft & Java

* **Minecraft:** 1.8 → Latest
* **Java:** 8+ (uses advanced features automatically on 11+ and 21+)

---

## Requirements

* **Java 8 or newer.**

    * Uses Java 11+ HTTP/2 client when available.
    * Uses Java 21+ virtual threads when available.
* **Network access** for source builds (Gradle/Maven wrappers or system `gradle`/`mvn`, if used).

---

## Installation

1. Download the latest `.jar` from **[Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/)**.
2. Place it into your server’s **`plugins/`** directory.
3. Start the server to generate default config files.
4. Edit **`plugins/AutoUpdatePlugins/config.yml`** and **`plugins/AutoUpdatePlugins/list.yml`**.
5. Run **`/update`** or **`/aup update`** (or restart) to kick off the first check.

> **Velocity / BungeeCord:** Same process — drop the jar in `plugins/`, configure, then trigger an update run.

---

## Quick Start

1. Add a few entries to `list.yml`:

   ```yaml
   ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
   Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
   EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
   ```
2. Leave `updates.interval` at the default (every 120 minutes), or set a cron schedule.
3. Optional: Add a GitHub token in `config.yml` to avoid rate limits.
4. Trigger a run:

   ```
   /update
   ```
5. New jars are staged atomically in your plugins folder (using temp/update paths under the hood). **Restart** the server to load the updated jars.

---

## Configuration

### `config.yml`

> The plugin **adds new options automatically** without overwriting your comments. Below is the full schema with defaults and notes.

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

**Key options explained**

* **`updates.schedule.cron` + `timezone`** — When set, cron **overrides** `interval`/`bootTime`.
* **`updates.key`** — GitHub PAT for Releases/Actions access and higher rate limits (especially useful for public repos under heavy use or any private repos).
* **`http.userAgents`** — Simple rotation to avoid brittle server-side filters.
* **`proxy`** — Full support for HTTP/SOCKS proxies.
* **`behavior.autoCompile`** — For GitHub repos: if there’s no release jar (or if the branch is newer than the last release by `branchNewerMonths`), the repo can be **built from source** using Gradle/Maven.
* **`paths`** — Customize temp/staging/output locations (falls back to sane defaults).
* **`performance`** — See [Performance Tuning Guide](#performance-tuning-guide).

---

### `list.yml`

A simple mapping of **plugin display name ➜ source URL (+ optional selectors)**.

```yaml
# A list of plugins to update.
# Format: <plugin-name>: <download-url>

ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
```

**Selectors you can use**

* **`[N]`** — Select the **N-th** jar asset from a release/build page. Example: `.../Essentials[3]`
* **`?get=<regex>`** — Choose files whose names match a regex. Example: `?get=.*(paper|spigot).*\.jar`
* **`?prerelease=true`** — Permit pre-releases.
* **`?autobuild=true`** — Force a source build on GitHub even if a jar asset exists.

> **Pro tip:** Combine selectors, e.g. `...?prerelease=true&get=.*spigot.*\.jar`.

---

### Scheduling Cheat Sheet

Common cron expressions (with examples using `America/New_York`):

* **Every 2 hours:** `0 */2 * * *`
* **Every day at 05:00:** `0 5 * * *`
* **Every Monday & Thursday at 03:30:** `30 3 * * 1,4`
* **Every 15 minutes:** `*/15 * * * *`

If cron is empty, the plugin uses **`interval`** (minutes) with an initial **`bootTime`** delay (seconds).

---

### Performance Tuning Guide

* **Java 21+ (virtual threads):** Keep `performance.maxParallel` higher (e.g., 8–16) for many small artifacts.
* **Java 11+ (HTTP/2):** Great default throughput. Tune `maxPerHost` if a single origin hosts most of your jars.
* **Java 8:** Increase `readTimeoutMs` if you see timeouts on large downloads; lower `maxParallel` if network is saturated.
* **Retries:** The plugin retries **`maxRetries`** times with exponential backoff (`backoffBaseMs` → `backoffMaxMs`).
* **Per-download timeout:** Set `perDownloadTimeoutSec` to protect against stuck transfers; keep `0` to disable.
* **I/O Paths:** Put `tempPath`/`updatePath` on fast local storage if possible.

---

## Commands & Permissions

### Commands

* **`/update`** — Trigger an update run.
* **`/aup download [names...]`** — Update all, or only the named plugins.
* **`/aup update [names...]`** — Alias of `download`.
* **`/aup stop`** — Request to stop the current updating process.
* **`/aup reload`** — Reload the plugin configuration.
* **`/aup add <name> <link>`** — Add a new entry to `list.yml`.
* **`/aup remove <name>`** — Remove an entry from `list.yml`.
* **`/aup list [page]`** — View the configured plugin list.
* **`/aup enable|disable <name>`** — Toggle an entry (comment/uncomment in `list.yml`).

### Permissions

* `autoupdateplugins.update` — Allows `/update`.
* `autoupdateplugins.manage` — Allows `/aup ...` commands.

> **Heads-up:** Most servers still require a **restart** to load updated jars. The plugin handles download & staging; you decide when to reboot.

---

## How It Works (Under the Hood)

1. **Discovery.** For each entry in `list.yml`, the plugin detects the provider (GitHub, Jenkins, SpigotMC, etc.) and computes the **latest matching artifact** using your selectors.
2. **Download (async).** Files are fetched with pooled connections; on Java 11+ it uses the HTTP/2 client; on Java 21+ it schedules downloads on virtual threads when available.
3. **Validation.** Optional **zip integrity** checks and duplicate detection (`name + size`) avoid unnecessary writes.
4. **Staging & Atomic Replace.** Files are written to a **temp** directory, then moved atomically into the **update/plugins** target so the server is never left in a partial state.
5. **Repeat.** According to your **interval** or **cron** schedule, with retries/backoff on transient failures.

---

## Examples for Every Source

> **Patterns below are illustrative.** Use your project’s actual URLs and apply selectors as needed.

* **GitHub Releases (pick 2nd asset, spigot-only):**

  ```
  MyPlugin: "https://github.com/Owner/MyPlugin[2]?get=.*spigot.*\\.jar"
  ```
* **GitHub Actions (force build from source if no jar):**

  ```
  MyActionsPlugin: "https://github.com/Owner/MyActionsPlugin?autobuild=true"
  ```
* **Jenkins (match shaded jar):**

  ```
  CoolThing: "https://ci.example.com/job/CoolThing/lastSuccessfulBuild/artifact/?get=.*-all\\.jar"
  ```
* **SpigotMC (resource page):**

  ```
  ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
  ```
* **dev.bukkit:**

  ```
  Vault: "https://dev.bukkit.org/projects/vault"
  ```
* **Modrinth (match platform flavor):**

  ```
  Fancy: "https://modrinth.com/plugin/fancy?get=.*(paper|spigot).*\\.jar"
  ```
* **Hangar:**

  ```
  HangarThing: "https://hangar.papermc.io/Owner/Project?get=.*spigot.*\\.jar"
  ```
* **CurseForge (filter file name):**

  ```
  CFThing: "https://www.curseforge.com/minecraft/bukkit-plugins/cfthing/files?get=.*release.*\\.jar"
  ```
* **Generic direct jar:**

  ```
  DirectJar: "https://downloads.example.com/plugins/DirectJar-1.2.3.jar"
  ```

---

## Troubleshooting & FAQ

**Q: Updates downloaded, but server didn’t change.**
A: Most platforms load jars only at startup. **Restart** your server after a run. Avoid hot-reloaders for complex plugins.

**Q: GitHub rate-limited / cannot access Actions artifacts.**
A: Add a **GitHub PAT** in `config.yml` → `updates.key`. For private repos, ensure the token has read access to Releases/Actions artifacts.

**Q: Wrong file selected.**
A: Add a **`?get=regex`** or use **`[N]`** to pick an asset index. Confirm the regex escapes dots (e.g., `\\.jar`).

**Q: Timeouts or slow downloads.**
A: Increase `readTimeoutMs`; decrease `maxParallel`; or set `perDownloadTimeoutSec` and tune `maxRetries`/backoff.

**Q: Need pre-releases.**
A: Append `?prerelease=true` to the URL (and ensure `behavior.allowPreRelease: true` if you want that globally).

**Q: Behind a corporate proxy.**
A: Configure `proxy.type/host/port`. If your proxy MITM-s TLS, ensure the Java trust store has the proxy CA.

**Q: Build from source didn’t trigger.**
A: Ensure `behavior.autoCompile.enable: true` and use `?autobuild=true` or let it kick in when no jar asset exists. The server must have outbound network access.

---

## Building from Source

Use **Maven**:

```bash
mvn -DskipTests package
```

The built `.jar` will be in `target/`.

---

## Security Notes

* Prefer **least-privileged tokens**. For GitHub, use a PAT limited to the repos you need with read permissions sufficient for Releases/Actions artifacts.
* Treat third-party download links as untrusted: keep `zipFileCheck: true`.
* Consider pinning sources with `?get=regex` to avoid accidentally switching to platform-incompatible jars.

---

## Contributing, Issues & Support

* **Bugs / Feature requests:** Open an issue:
  [https://github.com/NewAmazingPVP/AutoUpdatePlugins/issues](https://github.com/NewAmazingPVP/AutoUpdatePlugins/issues)
* PRs welcome! Please keep code style consistent and include a brief test plan in your PR description.

---

## License

AutoUpdatePlugins is licensed under the **MIT License**. See **`LICENSE`** for details.

---

### Changelog Snapshot (v12.x)

* Expanded provider coverage and smarter selection (`[N]`, `?get=regex`, `?prerelease`, `?autobuild`).
* HTTP stack upgrades (HTTP/2 on Java 11+, virtual threads on Java 21+).
* Better parallelism, retry/backoff, and connection pooling defaults.
* Comment-preserving config regeneration and richer scheduling controls.

---

