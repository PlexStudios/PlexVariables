# PlexVariables 1.0.1

**Beta / pre-release:** Automated validation is complete. Manual Paper testing is still pending.

> Build reusable PlaceholderAPI variables with conditions, expressions, nesting, and persistent storage without writing Java for every value.

**Paper 1.21+ · Java 21 · PlaceholderAPI**

PlexVariables is a configurable variable engine for Paper Minecraft servers. It allows server owners and developers to define custom PlaceholderAPI placeholders and dynamic stored variables in clean YAML files without writing Java for every value.

PlexVariables supports **static**, **conditional**, mathematical **expression**, and persistent **stored** variables. The expansion identifier is `plexvar`, so a variable named `kd_ratio` is available as `%plexvar_kd_ratio%`, while global stored variables can also be accessed via `%plexvar_global_<id>%`.

---

## Overview

A PlexVariables identifier is exposed through PlaceholderAPI as:

```text
%plexvar_<id>%
```

Global stored values can also be exposed through:

```text
%plexvar_global_<id>%
```

Variables can reference other variables, allowing small definitions to be composed into more advanced logic without requiring a custom plugin for every server-side value.

---

## Variable Types

| Type | Purpose | Syntax Key |
| --- | --- | --- |
| `STATIC` | Reusable fixed or placeholder-backed text values | `value` |
| `CONDITIONAL` | Select values using configurable condition rules evaluated top-to-bottom | `conditions`, `default` |
| `EXPRESSION` | Evaluate safe mathematical formulas compiled into an AST | `expression` |
| `STORED` | Persist player or global values using asynchronous SQLite storage | `type: stored`, `scope` |

---

## Features

- **Custom PlaceholderAPI Engine**: Define any placeholder dynamically in YAML.
- **Recursive Directory Discovery**: Place `.yml` files anywhere in `plugins/PlexVariables/variables/`, including nested folders.
- **Static, Conditional, Expression & Stored Types**: Full spectrum of variable resolution logic.
- **Nested Variable Composition**: Variables can reference other PlexVariables and external PlaceholderAPI placeholders.
- **Cycle & Depth Protection**: Automatic detection of circular references, stack depth limits (default 10), and total expansion work caps.
- **No Database I/O on Placeholder Hot Paths**: Stored variables are served directly from thread-safe in-memory caches.
- **Asynchronous Persistence**: SQLite reads and mutations are serialized on a dedicated storage executor; placeholder resolution uses in-memory caches.
- **Safe Mathematical AST Evaluator**: No arbitrary code execution; supports operators, parentheses, and math functions.
- **Transactional Reloads**: Safe configuration reload retains the current working state if newly loaded files have syntax errors.
- **In-Depth Debugging & Tracing**: `/pv test` gives step-by-step resolution traces showing intermediate values and token timings.

---

## Variable Types & Syntax

Put any `.yml` file under `plugins/PlexVariables/variables/`; subfolders are automatically scanned.

### 1. Static Variables

Static variables return configured text, with support for nested PlaceholderAPI placeholders and cross-variable references.

```yaml
variables:
  server_name:
    value: '&d&lPlex Network'
  welcome:
    type: static
    value: '&fWelcome %player_name% to %plexvar_server_name%!'
  max_players: 100
```

### 2. Conditional Variables

Conditional variables evaluate rules from top to bottom; the **first matching condition wins** and returns its value. If no condition matches, the optional `default` value is returned.

```yaml
variables:
  health_status:
    type: conditional
    conditions:
      - condition: "%player_health% <= 5"
        value: "&cCritical"
      - condition: "%player_health% <= 10"
        value: "&eLow"
    default: "&aHealthy"

  player_role:
    conditions:
      - condition: "permission:plexvariables.admin"
        value: "&cAdministrator"
    default: "&7Player"
```

#### Supported Condition Operators

| Operator | Type | Description |
| --- | --- | --- |
| `==`, `!=` | Numeric / String | Equality check (numeric if both parse as numbers; string obeys `conditions.case-sensitive`) |
| `>`, `>=`, `<`, `<=` | Numeric | Numeric comparison (operands must parse as numbers) |
| `contains`, `!contains` | String | Substring check (obeys `conditions.case-sensitive`) |
| `startsWith`, `endsWith` | String | Prefix / suffix check (obeys `conditions.case-sensitive`) |
| `matches` | Regex | Regular expression match |
| `permission:<node>` | Native | Checks if the online player has the specified permission node |
| `!permission:<node>` | Native | Checks if the online player lacks the specified permission node |
| `world:<name>` | Native | Checks if the online player is in the named world |
| `!world:<name>` | Native | Checks if the online player is not in the named world |

### 3. Expression Variables

Expression variables evaluate mathematical equations with operators, nested parentheses, and functions. Expressions are compiled at load time into an Abstract Syntax Tree (AST) before runtime evaluation.

```yaml
variables:
  simple_math:
    expression: "10 + 5"

  kd_ratio:
    type: expression
    expression: "%statistic_player_kills% / %statistic_deaths%"
    decimals: 2
    strip-trailing-zeros: true
    rounding-mode: HALF_UP
    on-error: "0"

  winrate:
    expression: "(%statistic_player_wins% / (%statistic_player_wins% + %statistic_player_losses%)) * 100"
    decimals: 1
    suffix: "%"
    on-error: "0%"

  formatted_number:
    expression: "1234567.89"
    decimals: 2
    format:
      thousands-separator: true
```

#### Mathematical Operators & Functions

| Symbol / Function | Description | Example |
| --- | --- | --- |
| `+`, `-`, `*`, `/` | Basic arithmetic | `10 + 5 * 2` |
| `%` | Modulo | `10 % 3` → `1` |
| `^` | Exponentiation (right-associative) | `2 ^ 3 ^ 2` → `512` |
| `min(a, b)` | Minimum of two numbers | `min(%kills%, 10)` |
| `max(a, b)` | Maximum of two numbers | `max(%kills%, 0)` |
| `abs(x)` | Absolute value | `abs(-15)` → `15` |
| `round(x)` | Round to nearest integer | `round(2.6)` → `3` |
| `floor(x)` | Floor to lower integer | `floor(2.9)` → `2` |
| `ceil(x)` | Ceiling to higher integer | `ceil(2.1)` → `3` |
| `sqrt(x)` | Square root | `sqrt(16)` → `4` |

### 4. Stored Variables (Player & Global)

Stored variables persist values to an embedded SQLite database (`data.db`). Placeholder evaluation is strictly **in-memory** via thread-safe caches; all database operations run asynchronously in the background.

```yaml
variables:
  player_gems:
    type: stored
    scope: player
    default: "0"

  server_event_status:
    type: stored
    scope: global
    default: "inactive"
```

- **Player Scope**: `%plexvar_player_gems%` evaluates the value stored for the specific player.
- **Global Scope**: `%plexvar_server_event_status%` evaluates the global server value. Global stored variables can also be referenced explicitly via `%plexvar_global_server_event_status%`.

---

## Commands & Permissions

Command alias: `/plexvariables`, `/plexvar`, `/pvar`, or `/pv`.

| Command | Purpose | Permission |
| --- | --- | --- |
| `/pv help` | Show command help | `plexvariables.use` |
| `/pv list [page]` | Show sorted variable IDs, types, and source files | `plexvariables.list` |
| `/pv reload` | Reload configuration and variable files transactionally | `plexvariables.reload` |
| `/pv parse <player\|--null> <text>` | Test and parse text containing placeholders | `plexvariables.parse` |
| `/pv test <player\|--null> <variable>` | Trace step-by-step evaluation of a variable | `plexvariables.test` |
| `/pv set <variable> <player\|global> <value>` | Set a stored variable value | `plexvariables.set` |
| `/pv add <variable> <player\|global> <amount>` | Add to a stored variable value | `plexvariables.add` |
| `/pv get <variable> [player\|global]` | Retrieve stored and default value | `plexvariables.get` |
| `/pv reset <variable> <player\|global>` | Reset stored variable to default (deletes DB row) | `plexvariables.reset` |

Permission `plexvariables.admin` grants all sub-permissions by default.

---

## Configuration

`plugins/PlexVariables/config.yml` settings:

| Setting | Default | Description |
| --- | ---: | --- |
| `max-resolution-depth` | `10` | Maximum nested PlexVariables stack depth (1..64) |
| `max-expansions` | `1000` | Total work budget cap per parse request (1..100000) |
| `max-output-length` | `65536` | Maximum allowed character length of output text |
| `warning-cooldown-seconds` | `60` | Cooldown period before repeating identical resolution warnings |
| `list-page-size` | `10` | Entries per page for `/pv list` |
| `error-value` | `""` | Safe fallback string on resolution errors or cycle detection |
| `colorize-placeholder-output` | `true` | Convert legacy and hex color codes in outputs |
| `conditions.case-sensitive` | `false` | Enable case-sensitive string condition matching |
| `storage.max-value-length` | `4096` | Maximum stored value character length |
| `storage.shutdown-timeout-seconds` | `10` | Timeout waiting for queued database writes on shutdown |

---

## Compatibility

| Requirement | Status |
| --- | --- |
| Server software | Paper |
| Minecraft | 1.21+ target |
| Java | 21 |
| PlaceholderAPI | Required |
| Runtime validation | Paper 1.21.11 |

---

## Installation & Build

### Installation
1. Install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/).
2. Place `PlexVariables-1.0.1.jar` in your server's `plugins/` directory.
3. Start or restart the server.
4. Customize your variables in `plugins/PlexVariables/variables/`.
5. Run `/pv reload` to apply updates without server downtime.

### Building from Source
PlexVariables uses Gradle. Build with the included wrapper:

- **macOS / Linux**: `./gradlew clean build`
- **Windows**: `.\gradlew.bat clean build`

The compiled shaded artifact is output to `build/libs/PlexVariables-1.0.1.jar`.

---

## Documentation

- [PlexDocs](https://github.com/PlexStudios/PlexDocs)
- [Developer API Guide](docs/API.md)
- [GitHub Wiki](https://github.com/PlexStudios/PlexVariables/wiki)
- [Issue Tracker](https://github.com/PlexStudios/PlexVariables/issues)

---

## Source Code & License

The PlexVariables source is published for transparency, review, learning, and contribution.

This repository is **source-available, not permissively open-source**. Redistribution, resale, rebranding, sublicensing, and publishing modified versions are not granted by default. See [LICENSE](LICENSE) for complete terms.

---

## Support

- For bug reports and feature requests, use the repository issue forms.
- For usage questions, consult [SUPPORT.md](SUPPORT.md).

---

## Plex Studios

Developed by **Applex** as part of **Plex Studios**.

- [Plex Studios](https://github.com/PlexStudios)
- [PlexDocs](https://github.com/PlexStudios/PlexDocs)
- [Website](https://applex.oriko.lk)

## Developer API

PlexVariables 1.0.1 introduces public API version 1 through Bukkit ServicesManager. Any third-party plugin can resolve variables, read persisted player/global overrides, and asynchronously set, add, or reset values. Optional immutable mutation contexts and closeable, owner-aware change subscriptions are included. Existing configurations and SQLite databases require no migration.

See [the public API guide](docs/API.md) for ExamplePlugin integration, result handling, subscription ownership, and callback threading.
