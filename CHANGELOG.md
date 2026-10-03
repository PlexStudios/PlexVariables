# Changelog

All notable changes to PlexVariables are documented here.

## 1.0.0

### Added

- Custom PlaceholderAPI variable engine.
- `STATIC` variables for reusable values.
- `CONDITIONAL` variables for configurable logic.
- `EXPRESSION` variables with safe mathematical evaluation.
- `STORED` variables with player and global scopes.
- Nested variable resolution.
- Cycle protection for variable chains.
- SQLite-backed persistent storage.
- Asynchronous persistence with cached placeholder resolution.
- Transactional configuration reload behavior.
- Debugging and variable test tooling.
- Placeholder identifiers using `%plexvar_<id>%`.
- Global stored aliases using `%plexvar_global_<id>%`.
