# PlexVariables Phase 1 design

Build a free Paper plugin for server owners to define PlaceholderAPI variables in YAML. Java 21, Gradle, Paper 1.21 API baseline, required external PlaceholderAPI, package `dev.plex.plexvariables`, identifier `plexvar`. No storage, conditions, expressions, or Phase 2 commands.

## Components and flow

`ConfigurationLoader` loads fresh local YAML objects, validated settings, messages, and automatically discovered variable files. `VariableManager` scans `.yml` files recursively without following symlinks, sorts relative paths lexicographically, validates definitions, and returns immutable data. Later valid definitions win normalized-ID collisions and report both sources. Bad variable files or entries are skipped with concise diagnostics.

`PluginState` is a single immutable snapshot of settings, messages, and variables. Startup loads once; `ReloadService` reads on a dedicated worker and publishes on the Paper main thread. Concurrent reload requests are rejected until the current one completes. A fatal config/messages/directory failure keeps the previous snapshot. A malformed individual variable file is skipped and its old definitions are removed on a successful reload. An in-flight resolution keeps its original snapshot.

`VariableResolver` reads no files and performs no player lookup. It resolves nested PlexVariables and PlaceholderAPI values using nullable `OfflinePlayer`. A thread-local request context carries a stack/set, depth, and total work budget through expansion callbacks. Limits also bound output growth. Warnings are bounded and throttled, identify variable/source/cause, and never produce routine stack traces. Unknown IDs return null to PlaceholderAPI (preserving the token); cycles, limits, and expansion exceptions return configurable safe text. Literal values take a fast path; player-sensitive results are never globally cached.

`PlexVariablesExpansion` persists through PAPI reload and is unregistered on plugin disable. Commands execute on the main thread. Parsing first checks exact online players, then Paper's cached offline-player lookup; no blocking profile/network lookup. `--null` explicitly selects a null context. List output is paginated and sorted. Permission checks cover each subcommand and tab completion.

`MessageUtil` stores immutable templates from messages.yml. Templates opt into the prefix by including `%prefix%`. Replacements are single-pass so replacement values cannot inject other message parameters. Adventure handles command output. `ColorUtil` accepts ampersand legacy codes, `&#RRGGBB`, and `&x&R&R&G&G&B&B`; expansion output directly translates these to section-sign legacy colors by default, configurable off. Direct translation preserves color-only values and trailing formatting for composable placeholders. MiniMessage is not accepted.

## Shared interfaces

- `VariableDefinition(String id, String value, String sourceFile, VariableType type)`; `VariableType.STATIC` only for now.
- `PluginSettings(int maxResolutionDepth, int maxExpansions, int maxOutputLength, int warningCooldownSeconds, int listPageSize, String errorValue, boolean colorizePlaceholderOutput)`; `defaults()` returns `(10, 1000, 65536, 60, 10, "", true)`; `from(ConfigurationSection)` validates settings in the root YAML with depth 1..64, expansions 1..100000, output 1..1048576, cooldown 1..3600, page size 1..100, fallback without percent tokens and within output size. Invalid settings throw `IllegalArgumentException`.
- `PluginState(PluginSettings settings, MessageUtil messages, Map<String, VariableDefinition> variables, int filesLoaded)` defensively copies the map.
- `VariableManager(Logger logger).load(Path folder)` returns nested `LoadResult(Map<String, VariableDefinition> variables, int filesLoaded, int skippedFiles)` and throws `IOException` for directory scan failure. Invalid individual files are warnings.
- `ConfigurationLoader(Path dataDirectory, Logger logger).load()` returns `PluginState`, throws `IOException` on fatal configuration failure. Root installs resources before loading. ConfigurationLoader reads messages via `MessageUtil.from(ConfigurationSection)`.
- `MessageUtil.from(ConfigurationSection root)`; `send(CommandSender sender, String key, Map<String,String> replacements)` and `send(sender,key)`; `render(String key, Map<String,String>)` returns `List<Component>`. Missing required keys are filled from bundled messages.yml in memory; malformed custom values fail validation.
- `VariableResolver(Supplier<PluginState> state, BiFunction<OfflinePlayer,String,String> parser, Logger logger)`; `resolve(OfflinePlayer player, String id)` returns nullable string; `parse(OfflinePlayer player, String text)` parses arbitrary text, shares the same guard; `clearWarnings()` resets throttling after reload.
- `ColorUtil.component(String)` returns Adventure `Component`; `ColorUtil.legacy(String)` returns section-sign serialized text.
- `ReloadService(JavaPlugin plugin, ConfigurationLoader loader, AtomicReference<PluginState> state, Runnable afterReload)`; `boolean reload(CommandSender sender)` handles async load and synchronous reporting; `close()` stops work on disable.

## Decisions and limitations

The user supplied a complete specification and explicitly requested generation after the architecture/file list, so execution proceeds without additional design approvals. The empty workspace is not a Git repository; no worktree or commits are needed. Paper's 1.21 API is deliberately used instead of the newest API to keep the requested baseline. No claim of compatibility with every later Minecraft release is made without a server test. Third-party expansions run on the caller's thread and retain their own threading requirements. No executor can safely make arbitrary third-party expansions thread-safe. An expansion that blocks internally cannot be time-limited safely by this plugin.

Validation includes blank/missing sections, scalar string/number/boolean values (converted to text), invalid IDs, malformed YAML, duplicate normalized IDs, unsupported variable types, cycle/depth/work/output limits, null players, changing player values, PAPI reentry, concurrent snapshots, warning suppression, and literal message replacement. Resource defaults are created only when absent; example files are seeded only when the variables directory is first created.

## Verification

JUnit tests run without a live server using real Bukkit YAML and injected parsing functions. Gradle compiles against actual APIs and builds a plugin JAR with expanded version metadata and no shaded dependencies. A manual Paper checklist covers registration, permissions, command behavior, reload lifecycle, and real PAPI expansions; those checks must be labeled unrun unless an actual server is exercised.
