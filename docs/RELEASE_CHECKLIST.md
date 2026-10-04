# PlexVariables v1.0.0 Release Checklist

Use this checklist to perform pre-release verification of PlexVariables v1.0.0 artifacts and live server functionality.

- [x] Clean build passes without errors (`./gradlew clean check build`)
- [x] All 89 automated unit tests pass (100% success rate)
- [x] Installed on fresh Paper 1.21+ server with PlaceholderAPI
- [x] Plugin initializes cleanly without console errors or warnings
- [x] Default configuration files & directories seeded on first startup
- [x] `%plexvar_<static_variable>%` parses correctly
- [x] Nested PlexVariables and PlaceholderAPI placeholders resolve accurately
- [x] Conditional variables evaluate correctly (`permission:`, `world:`, operators, default branch)
- [x] Expression variables evaluate correctly (+, -, *, /, %, ^, parentheses, functions)
- [x] Division and modulo by zero fallback safely to `on-error` or `error-value`
- [x] Stored player variables persist and update asynchronously (`/pv set`, `/pv add`, `/pv get`, `/pv reset`)
- [x] Stored global variables persist and update asynchronously (`/pv set`, `/pv add`, `/pv get`, `/pv reset`)
- [x] Global stored alias (`%plexvar_global_<id>%`) resolves correctly
- [x] Stored values survive full server restart
- [x] Reset command deletes DB row and restores unpersisted default value
- [x] `/pv reload` updates state transactionally without breaking existing resolution
- [x] Broken YAML configuration on reload safely rolls back to previous state
- [x] `/papi reload` maintains `%plexvar%` expansion registration
- [x] Variable cycle protection halts infinite recursion cleanly with throttled warning
- [x] Command permissions enforce access rules for all subcommands
- [x] Command tab completion accurately completes subcommands, players, and stored IDs
- [x] Server shutdown (`stop`) cleanly releases SQLite connections and terminates thread executor
- [x] Distributable shadow JAR checked (`build/libs/PlexVariables-1.0.0.jar`)
- [x] README.md reviewed and reflects v1.0.0 capabilities accurately
- [x] Version tag confirmed as `1.0.0` across `build.gradle` and `plugin.yml`
