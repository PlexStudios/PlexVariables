# Changelog

All notable changes to PlexVariables will be documented in this file.

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
