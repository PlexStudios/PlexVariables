# PlexVariables

> Build reusable PlaceholderAPI variables with conditions, expressions, nesting, and persistent storage — without writing Java for every value.

**Paper 1.21+ · Java 21 · PlaceholderAPI**

PlexVariables is a configurable variable engine for Paper servers. It lets server owners define custom PlaceholderAPI values in YAML and compose them into larger systems using static values, conditional logic, safe mathematical expressions, nested variables, and persistent player or global storage.

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

## Variable Types

| Type | Purpose |
| --- | --- |
| `STATIC` | Reusable fixed or placeholder-backed values |
| `CONDITIONAL` | Select values using configurable conditions |
| `EXPRESSION` | Evaluate safe mathematical expressions |
| `STORED` | Persist player or global values using local SQLite storage |

## Features

- Custom PlaceholderAPI variables defined through YAML
- Static, conditional, expression, and stored variable types
- Nested variable composition
- Player-scoped and global persistent values
- SQLite-backed storage
- Asynchronous persistence
- No database I/O during normal placeholder resolution
- Safe expression parsing rather than arbitrary code execution
- Cycle protection for nested variables
- Transactional reload behavior
- Debugging and test commands for resolving values
- Designed to stay practical for both simple and advanced server setups

## Compatibility

| Requirement | Status |
| --- | --- |
| Server software | Paper |
| Minecraft | 1.21+ target |
| Java | 21 |
| PlaceholderAPI | Required |
| Runtime validation | Paper 1.21.11 |

Compatibility claims are kept separate from runtime testing. Additional versions should only be listed as tested after they have actually been validated.

## Installation

1. Install PlaceholderAPI.
2. Place the PlexVariables JAR in the server's `plugins` directory.
3. Start or restart the server.
4. Configure your variables in the generated PlexVariables files.
5. Reload supported configuration after making changes.

## Example

A simple variable can be referenced through PlaceholderAPI anywhere another compatible plugin accepts placeholders.

```text
%plexvar_rank%
```

Variables can then be composed so one definition builds on another rather than duplicating logic throughout multiple plugins or configurations.

## Commands

PlexVariables includes administration and debugging commands for managing configuration and testing resolved values.

Detailed command and permission documentation will live in the project Wiki and PlexDocs so the README can remain focused.

## Documentation

- [PlexDocs](https://github.com/PlexStudios/PlexDocs)
- GitHub Wiki — project-specific configuration, variable types, examples, storage behavior, and troubleshooting
- [Issue tracker](https://github.com/PlexStudios/PlexVariables/issues)

## Source Code

The PlexVariables source is published for transparency, review, learning, and contribution.

This repository is **source-available, not permissively open-source**. Redistribution, resale, rebranding, sublicensing, and publishing modified versions are not granted by default. See [LICENSE](LICENSE) for the repository terms.

## Contributing

Contributions are welcome when they fit the direction of PlexVariables.

Before opening a pull request, read [CONTRIBUTING.md](CONTRIBUTING.md). For reproducible bugs or feature proposals, use the repository issue forms.

## Support

See [SUPPORT.md](SUPPORT.md) before opening an issue.

## Changelog

Release history is maintained in [CHANGELOG.md](CHANGELOG.md).

## Plex Studios

Developed by **Applex** as part of **Plex Studios**.

- [Plex Studios](https://github.com/PlexStudios)
- [PlexDocs](https://github.com/PlexStudios/PlexDocs)
- [Website](https://applex.oriko.lk)
