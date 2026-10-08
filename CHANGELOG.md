# Changelog

All notable changes to PlexVariables will be documented in this file.

## 1.0.1

### Added
- Public API version 1 through Bukkit ServicesManager with definition lookup, existing resolver access, explicit player/global persisted reads, and asynchronous set/add/reset operations.
- Immutable mutation context, result statuses, and raw change records. All mutation methods include simple context-free overloads.
- Closeable change subscriptions and owner-aware cleanup on consumer disable or provider shutdown, including reference release from retained handles.
- API documentation with ExamplePlugin examples and regression coverage for API contracts, real SQLite failures/commit visibility, subscription lifecycle, commands, placeholders, restart, and unchanged schema.

### Changed
- Shared command/API mutations notify once after successful commit and cache update, only when effective stored text changes. Successful raw override transitions still return SUCCESS when a default makes notifications unnecessary.
- Numeric additions serialize persisted reads and writes; strict API missing/nonnumeric handling preserves the existing command zero-start behavior.
- Provider implementation resides outside the public API package. No storage managers, SQLite classes, repositories, or caches are exposed by the service interface.
- Project and packaged plugin version advance to 1.0.1. Existing configuration, variable files, permissions, commands, placeholders, and SQLite schema remain compatible; no migration is required.
- Subscriber failure diagnostics remain redacted and bounded to the first failure per registration.

### Fixed
- Owner subscriptions cannot be recreated by later disable-event listeners while the owner still reports enabled; re-enable starts a valid new subscription lifecycle.
- Forced storage shutdown completes queued, unstarted operations instead of leaving their futures pending, while committed writes retain successful results.
- Command additions retain the configured nonnumeric error message for invalid persisted values and defaults in both scopes.
- Shutdown and failed-publication tests assert callback counts outside subscriber code, avoiding swallowed assertion failures.

### Validation
- Fresh baseline clean/test/build passed with Java 21: 99 tests, zero failures/errors/skips.
- Final uncached Java 21 clean/test/build passed: 128 tests, zero failures/errors/skips; all build tasks executed. This adds 29 tests to the 99-test workspace baseline.
- Packaged plugin.yml reports 1.0.1. SQLite JDBC is included; provided Paper and PlaceholderAPI classes are excluded. Runtime dependencies remain unchanged.
- Case-insensitive project and artifact audits returned zero prohibited consumer references. Whitespace checks passed and no Java source line comments were introduced.
- Live Paper integration tests have not been performed.

## [1.0.0] - 2026-10-01

### Added
- **Static Variables**: Configure constant text, nested PlaceholderAPI placeholders, and cross-file PlexVariable references in automatically discovered YAML files.
- **Conditional Variables**: Evaluate dynamic logic using numerical comparison (`==`, `!=`, `>`, `>=`, `<`, `<=`), string comparison (`contains`, `!contains`, `startsWith`, `endsWith`, `regex`), and native Paper conditions (`permission:`, `world:`).
- **Expression Variables**: Perform mathematical operations (`+`, `-`, `*`, `/`, `%`, `^`, unary minus/plus, parentheses, `sqrt`, `min`, `max`, `abs`, `round`, `floor`, `ceil`) with customizable decimal rounding, thousands separators, prefixes, suffixes, and division-by-zero error fallbacks.
- **Stored Variables (Player & Global)**: Dynamic SQLite-backed player and global variables with zero-disk-overhead in-memory caching, atomic versioning, and asynchronous persistence.
- **Global Variable Alias**: `%plexvar_global_<id>%` syntax for direct resolution of global stored variables.
- **Command Management System**: Full command executor for `/pv help`, `/pv list`, `/pv reload`, `/pv parse`, `/pv test`, `/pv set`, `/pv add`, `/pv get`, and `/pv reset` (with `/pv remove` alias).
- **Evaluation Trace Engine**: Detailed trace debugging for `/pv test` showing raw expressions, resolved tokens, math steps, condition matching, cache status, and execution timings.
- **Safety & Work Budget Guards**: Cycle detection, max resolution depth limits, total expansion work budgets, max output length caps, and throttled warning outputs.
- **Transactional Reloads**: Atomic background configuration reloading that retains the previous working snapshot if a reload fails due to syntax or configuration errors.
- **PlaceholderAPI Integration**: Native expansion registration under identifier `%plexvar%`.

### Performance & Security
- Zero disk I/O on placeholder hot paths.
- PreparedStatements for all SQLite interactions with strict string length validation and path safety.
- Immutable snapshot architecture for lock-free read operations during placeholder resolution.
