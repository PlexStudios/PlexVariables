# PlexVariables 1.0.1 Public API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Release a standalone, consumer-neutral public API with committed asynchronous mutations and reliable subscriptions in PlexVariables 1.0.1.

**Architecture:** Harden the existing service facade and serialized storage path without replacing either. Keep public contracts as nested immutable models in one interface; move the concrete facade outside the API package. Commands retain their adapters and existing numeric semantics while sharing persistence and notification behavior.

**Tech Stack:** Java 21, existing Paper 1.21 API, PlaceholderAPI, SQLite JDBC, Gradle wrapper, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-08-public-api-design.md` (approved; leave unchanged).

## Global Constraints

- Use Java 21 and the existing Paper target, dependencies, resolver, configuration snapshots, and single storage executor.
- Preserve all released commands, permissions, aliases, placeholders, configuration keys, variable definitions, and SQLite data.
- No schema migration is needed or planned.
- Do not introduce source line comments, reflection, production mocks, or speculative abstractions.
- No consumer-specific names, imports, artifacts, events, configuration, or runtime assumptions may remain in project files.
- Target release 1.0.1; plugin metadata continues to expand the Gradle version.
- Preserve pre-existing workspace changes; stage only task-owned changes when committing. No pushes, releases, or deployment are part of this plan.

## Review Focus

- Reload changes a configured default while writes are queued: each mutation uses the definition snapshot validated when submitted; caches retain raw overrides.
- Setting the default and resetting that override both persist raw state but emit no effective-value notification.
- An owner closes its subscription from a callback or disables during another callback: no deadlock, later callback entry is prevented, and retained handles release references.
- A database read fails before mutation calculation: return persistence failure without fabricated old/new state or cache changes.
- Numeric representations differ (`1.0` versus `1`): compare stored strings for effective change, preserving existing resolver string semantics; BigDecimal addition still uses plain normalized output.

## File map

- Modify `src/main/java/dev/plex/plexvariables/api/PlexVariablesApi.java`: sole public service interface, nested records/enums, subscription and read exception.
- Move `src/main/java/dev/plex/plexvariables/api/DefaultPlexVariablesApi.java` to `src/main/java/dev/plex/plexvariables/implementation/DefaultPlexVariablesApi.java`: validation, public operation adapters, owner lifecycle listener.
- Modify `src/main/java/dev/plex/plexvariables/storage/StorageManager.java`: serialized mutation results, effective-change filtering, subscription ownership and reference release.
- Modify `src/main/java/dev/plex/plexvariables/storage/SQLiteStorage.java`: verify auto-commit before write/delete operations; retain schema and SQL behavior.
- Modify `src/main/java/dev/plex/plexvariables/PlexVariables.java`: provider lifecycle wiring and cleanup.
- Modify `src/main/java/dev/plex/plexvariables/command/PlexVariablesCommand.java`: only shared-path arguments/adapters needed for defaults and results; preserve visible behavior.
- Modify existing API, command, stored-variable and resolver tests; create `src/test/java/dev/plex/plexvariables/api/ApiLifecycleTest.java` for service/owner wiring.
- Modify `build.gradle`, `README.md`, `docs/API.md`, and `CHANGELOG.md`: version and accurate release documentation.

## Execution environment

Fresh baseline already executed successfully before product changes: 99 tests, zero failures/errors/skips, clean build successful. The sandbox blocks local Gradle daemon communication; approved execution outside the sandbox with the workspace-local cache succeeds. Use the same context for subsequent Gradle commands, with this PowerShell setup in each command:

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'
.\gradlew.bat test --no-daemon
```

For targeted cycles append `--tests` filters. Do not change project JVM configuration to bypass the environment issue. Run a fresh final clean/test/build regardless of targeted results.

### Task 1: Immutable public contracts and explicit operations

**Files:** Public API interface, moved provider, existing API tests, main-plugin import, and minimal StorageManager/command call-site adaptations for immutable model accessors.

**Interfaces:** Retain `variable(String)` and `resolve(OfflinePlayer, String)`. Add `CompletableFuture<Optional<String>> getStoredPlayerValue(UUID, String)` and `getStoredGlobalValue(String)`. Set/add/reset return `CompletableFuture<MutationResult>`; their exact names and parameters are those in the approved spec, with a second overload appending `MutationContext context`. Retain `Subscription subscribe(Consumer<VariableChange>)`; Task 3 adds the owner overload when its real implementation is ready.

Nested types: `MutationContext(String source, Optional<String> correlationId, Map<String,String> metadata)` with convenience `MutationContext(String source)`; `VariableChange(String variable, Scope scope, Optional<UUID> playerId, Optional<String> oldValue, Optional<String> newValue, Optional<MutationContext> context)`; `MutationResult(Status status, Optional<VariableChange> change)` with `boolean success()`; `Status` contains exactly the seven spec statuses. Nested `ReadException` carries Status, with sanitized text and no exposed storage type. Keep existing VariableInfo unchanged unless validation requires it.

- [x] Add failing tests asserting `new MutationContext("ExamplePlugin").metadata().isEmpty()`, defensive metadata copy, rejected blank source/null map entries, validated scope/UUID combinations, and result invariants: success/no-change require a change; failure statuses forbid one.
- [x] Run the API test filter and record the real failure; missing public contracts may initially cause compilation failure.
- [x] Implement the immutable nested contracts and explicit player/global methods. Context-free overloads delegate internally without a context; context-taking overloads require a nonnull context. Keep shared scope/UUID validation private to the provider; do not expose a null-target public method. Remove unpublished opaque-cause methods and update draft tests to the released contract.
- [x] Adapt the existing real storage mutation path and command value access to Optional fields/typed contexts so this task compiles and retains existing behavior. Initially translate existing completed changes to SUCCESS or NO_CHANGE by their raw old/new values, and actual storage exceptions to failure results; Task 2 moves complete status classification into storage and adds effective-default comparison. Do not add stub owner handlers or fake successful results.
- [x] Move the concrete provider and update main/test imports. API package imports must be Java/Bukkit/public API types only. The provider translates internal read failures to ReadException.
- [x] Run `--tests '*PlexVariablesApiTest'`; verify definition lookup, resolver thread requirement, scope rejection, absent reads and every context-free overload. Commit only these task changes after they pass.

### Task 2: Committed mutation results and effective-value notifications

**Files:** StorageManager, SQLiteStorage, provider, command, API tests and StoredVariableTest.

**Interfaces:** Internal `CompletableFuture<MutationResult> mutateStored(String variable, Scope scope, UUID playerId, UnaryOperator<String> mutation, String defaultValue, MutationContext context)` and `addStored(String variable, Scope scope, UUID playerId, BigDecimal amount, String startingDefault, String effectiveDefault, MutationContext context)`. Internal nullable values are permitted; public models use Optional. Existing storage setter/deleter methods continue returning CompletableFuture<Void> and translate failed results into their existing failure path. The command add adapter obtains the new raw value from the successful result; it retains its explicit zero starting default independently of the effective configured default.

- [x] Add failing assertions: setting absent `score` to its default `10` returns SUCCESS with empty old/raw `10` new and zero notifications; reset returns SUCCESS with raw `10` old/empty new and zero notifications; repeated reset returns NO_CHANGE. Add decimal, numeric-format, negative, zero-delta, missing-default and nonnumeric tests.
- [x] Run API/storage filters; record actual failures against the draft behavior.
- [x] Implement status mapping on the storage executor. Read persisted state, calculate, validate length, detect identical raw state, write/delete under verified auto-commit, update raw caches, compare old/new effective strings against the supplied default, then notify only when different. Capture defaults from the validated definition snapshot before submission.
- [x] Preserve command fallback and failure handling. Existing command setters/deleters receive the configured default for notification comparison without requiring a second configuration source. Keep internal standalone storage calls valid when no definition default is available.
- [x] Add a real SQLite BEFORE INSERT/UPDATE trigger using a second test connection with `RAISE(ABORT, 'test write rejected')`; assert PERSISTENCE_FAILED, empty result change, unchanged persisted value/cache, and zero new notifications. Drop the trigger after the assertion. Use a separate test-only database fixture with a missing table to prove failed reads likewise produce no change model. Never alter production schema for testability.
- [x] In a successful callback read through a second SQLite connection and assert the new row is visible, proving commit precedes notification. Assert callback thread differs from the caller and matches the storage executor. Submit 100 additions and verify the final authoritative value. Change the definition snapshot after submission and verify that queued mutations use the captured default.
- [x] Run API/storage filters and existing command/resolver filters; verify no regressions. Commit task changes only after passing.

### Task 3: Ownership, unsubscribe, and lifecycle wiring

**Files:** StorageManager, provider, main plugin, ApiLifecycleTest and API tests.

**Interfaces:** Internal owner-aware registration plus `void unsubscribeOwner(Plugin owner)`. The concrete provider implements Bukkit Listener and AutoCloseable; `onPluginDisable(PluginDisableEvent)` removes that owner's subscriptions, and `close()` prevents new registrations and clears active subscriptions. Main plugin stores the concrete provider privately, registers its listener before publishing the service, and closes/unregisters it on disable, including partial initialization failure.

- [x] Add failing tests for disabled-owner rejection, off-server-thread owner registration rejection, owner-specific disable, repeated close, subscriber self-close, later-subscriber close during publication, and registration after shutdown.
- [x] Run lifecycle/API filters and record failures.
- [x] Implement a small private registration type with clearable owner/listener/removal references and idempotent close. Synchronize only lifecycle entry/state changes; never hold a shared lifecycle lock while invoking consumer code. A callback already entered may finish. Close from within a callback must work.
- [x] Test reference release deterministically through the private registration's implementation-focused tests in its owning package if necessary; do not rely on GC timing or add public introspection. Assert owner/listener/removal references are cleared after close, owner disable and provider shutdown.
- [x] Use Bukkit SimpleServicesManager and mocked plugin/server infrastructure to exercise actual provider publication/discovery/unregistration wiring. Exercise production lifecycle entry points with the project's existing Mockito support; do not merely test that a mocked ServicesManager accepts register. Verify provider cleanup is invoked on failed/partial enable where initialization can be exercised without a running server.
- [x] Assert subscriber exceptions do not affect committed result or later subscribers, and diagnostics remain redacted/logged once. Test shutdown clears manually managed and owned listeners; drained work may persist but must not call cleared subscriptions.
- [x] Run lifecycle/API filters and commit the passing task changes.

### Task 4: Compatibility, restart, and schema regression coverage

**Files:** Existing command, resolver, stored-variable and API tests.

**Interfaces:** All production interfaces from Tasks 1–3; existing command/placeholder contracts remain unchanged.

- [x] Add tests for command set/get/add/reset/remove for both scopes, permissions/aliases already supported, and zero-start command add when API add reports MISSING_VALUE. Assert command-triggered effective no-ops do not notify and real changes notify once.
- [x] Add restart tests: persist player/global rows, close/reopen storage, verify authoritative reads and global cache, load player cache and verify placeholder/default/global-alias behavior. Assert schema_version remains `1` and production table/column names are unchanged.
- [x] Run these tests; investigate any failure before production edits. Any required fix must be local to the affected shared API/storage path, not a rewrite of released commands/resolver behavior.
- [x] Run the full `test --no-daemon` suite and record actual totals. Commit passing regression coverage and any necessary local fixes.

### Task 5: Version and consumer documentation

**Files:** build.gradle, README.md, docs/API.md, CHANGELOG.md.

**Interfaces:** Finished public contracts and actual behavior from Tasks 1–4.

- [x] Set project version to `1.0.1`; retain plugin.yml version expansion and all existing metadata/dependencies.
- [x] Document ExamplePlugin service discovery, compile-only packaging and plugin dependency, definition lookup/resolve, both reads, context-free/context-aware set/add/reset, status handling, Optional raw values, raw versus effective changes, executor callbacks, owner registration and manual close. Examples must use real API signatures and clearly identify example variable definitions as consumer configuration, not installed project data.
- [x] Add `## 1.0.1` to CHANGELOG.md; preserve the historical 1.0.0 section. Describe actual implemented changes, no migration, and only verification already performed. Remove prohibited consumer prose from all project-owned files. Correct README claims about storage batching if they misdescribe the actual shared implementation.
- [x] Scan docs for removed draft methods and unsupported claims; build and inspect packaged plugin.yml for exactly `1.0.1`. Commit release documentation/version changes after inspection.

### Task 6: Final verification and report

**Files:** Final project diff, generated test XML/JAR, CHANGELOG.md validation entries.

- [x] Run `$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'` then `.\gradlew.bat clean test build --no-daemon` in the approved execution context. Require BUILD SUCCESSFUL before claiming the build passed.
- [x] Sum tests/failures/errors/skipped from fresh `build/test-results/test/TEST-*.xml`. Verify packaged version and shaded contents: JDBC included, provided Paper/PlaceholderAPI APIs excluded, no consumer artifacts. Review build dependency declarations and resolved runtime dependencies.
- [x] Run `git diff --check`; inspect all new/untracked sources too. Search project-owned tracked/untracked files and hidden project directories case-insensitively for the prohibited consumer token; exclude Git object storage and third-party Gradle caches. Inspect generated project resources/JAR separately. Require zero matches. Keep the token in the verification command only, never a repository artifact.
- [x] Scan added Java lines for source line comments, distinguishing URL/string/regex syntax; inspect API imports and signatures for implementation leakage. Check unused imports, sensitive/debug data, duplicate paths, shutdown ownership, and outstanding placeholders.
- [x] Update changelog validation with actual final counts/build outcome and note that a live Paper smoke test was not run unless it actually was. Documentation-only validation edits do not change the tested JAR behavior; any source/build change requires a fresh affected test and final build.
- [x] Perform final whole-diff review against the approved spec, preserving existing functionality and schema. Report files created/changed, API contracts, mutation/subscription semantics, version, compatibility, migration status, exact test/build outcomes, reference/dependency audit results, and any real limitations. Do not claim completion while a required check is failing or incomplete.

## Plan self-review and handoff

Every specification section maps to Tasks 1–6; the five Review Focus cases are covered in Tasks 2–4. Public model/property names and internal mutation signatures are consistent across tasks. No new product dependencies, public internals, schema changes, or consumer-specific features are planned.

Recommended execution method: native implementation in this chat using executing-plans. These tasks share a small service/storage boundary and benefit from preserving the current workspace context. The user approved this plan and selected native execution. Tasks 1–6 are complete.

## Execution results

- Fresh pre-change baseline: 99 tests, zero failures/errors/skips, clean build passed.
- Final uncached `clean test build dependencies --configuration runtimeClasspath --no-daemon --no-build-cache`: 128 tests, zero failures/errors/skips; all eight tasks executed, BUILD SUCCESSFUL.
- Packaged version 1.0.1; Java class version 65; JDBC included; Paper/PlaceholderAPI excluded; runtime dependency remains SQLite JDBC only.
- Project and generated JAR consumer-name searches: zero matches. No added Java source line comments. Whitespace checks passed. Approved spec and source resources unchanged; historical 1.0.0 changelog unchanged. No schema migration.
- Independent final review identified owner-disable re-registration, forced-shutdown queued futures, command numeric-error localization, and swallowed callback test assertions. All were addressed; three reproducing regressions failed before fixes and passed afterward, followed by the full green suite. A legitimate owner re-enable test was also added. No review findings are deferred.
- No live Paper smoke test or benchmark was performed. Existing Gradle deprecation warnings remain.

Execution decisions: preserve the initial draft in the current checkout on a feature branch rather than copying it to a clean worktree; use a PowerShell ledger instead of Bash-only scripts; allow package-private registration state for deterministic internal reference-release tests; treat callback acquisition as entry so already acquired callbacks may finish; use weak disabled-owner markers and explicit unstarted-task rejection without changing bounded shutdown of active work; retain existing unrelated cache architecture and avoid performance claims. Costs are filesystem isolation, manual bookkeeping, internal test coupling, in-flight callback tolerance, and the existing limits on active shutdown work. Live server verification remains separate. Work is retained on the feature branch without merge, push, or release publication.