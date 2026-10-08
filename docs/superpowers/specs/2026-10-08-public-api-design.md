# PlexVariables 1.0.1 public API specification

## Purpose and constraints

Expose a practical service API for any third-party Paper plugin. PlexVariables remains standalone: no consumer-specific names, imports, artifacts, events, configuration, or runtime assumptions may remain in project files. Dependency direction is consumer to public API.

Use Java 21 and the existing Paper target, dependencies, resolver, configuration snapshots, and single storage executor. Preserve all released commands, permissions, aliases, placeholders, configuration keys, variable definitions, and SQLite data. No schema migration is needed or planned. Do not introduce source line comments, reflection, production mocks, or speculative abstractions.

The workspace already contains uncommitted API, command, storage, and documentation changes. Preserve their working behavior and harden them in place. The public 1.0.0 release has no API, so the unpublished draft interface can be refined before its first release without breaking a released API contract.

## Architecture and alternatives

Extend the existing service facade and shared persistence path. The public interface and immutable models belong in the API package; the concrete provider belongs in an implementation package and is not a consumer dependency. The interface must not import storage, configuration, resolver, cache, or other implementation types.

A separate API artifact would add publishing and dependency complexity without being required for this release. A new storage or event framework would duplicate existing infrastructure and increase compatibility risk. Neither is included.

The existing Bukkit ServicesManager registration remains the discovery mechanism. Register only after successful plugin initialization and unregister on disable. Consumers use the interface with a compile-only dependency, never a main-plugin cast or static internals.

## Public operations

Retain definition lookup and server-thread variable resolution using the existing resolver. Definition metadata is an immutable snapshot; unknown IDs return an empty Optional. Resolution keeps existing unknown-variable, default, formatting, and placeholder behavior. Resolution must document its server-thread requirement and cache semantics.

Expose these explicit stored operations:

- `getStoredPlayerValue(UUID playerId, String variable)` and `getStoredGlobalValue(String variable)`.
- `setStoredPlayerValue(UUID playerId, String variable, String value)` and its global equivalent.
- `addStoredPlayerValue(UUID playerId, String variable, BigDecimal amount)` and its global equivalent.
- `resetStoredPlayerValue(UUID playerId, String variable)` and its global equivalent.

Each mutation also has an overload accepting MutationContext. Normal usage requires no context, correlation ID, or null player argument. Reads return CompletableFuture of Optional string containing the raw persisted override, not a substituted default. Mutations return CompletableFuture of MutationResult. Player and global methods validate the configured scope and normalize IDs consistently with the existing resolver.

Null required arguments, blank IDs, unknown stored definitions, and scope mismatches are rejected with documented argument exceptions before submission. Numeric, value-limit, storage-lifecycle, and persistence failures use mutation statuses. Read persistence failures complete exceptionally using an API-owned exception without requiring callers to import StorageException. No mutable implementation objects escape through public signatures.

## Immutable models

Keep models small, using records and enums:

- Scope: PLAYER or GLOBAL.
- MutationContext: nonblank source, optional correlation identifier, and immutable string metadata copied at construction. Context is optional for operations. Source strings and metadata are carried unchanged and never interpreted. No arbitrary mutable caller object is part of the new contract.
- VariableChange: variable ID, scope, optional player UUID, optional old and new raw overrides, and optional mutation context. Validate that player scope has a UUID and global scope does not. Optional values represent absent persisted rows explicitly.
- MutationResult: status and optional VariableChange. Successful writes and no-ops include old/new state; failures do not imply that unavailable state was read successfully. Statuses distinguish SUCCESS, NO_CHANGE, MISSING_VALUE, NON_NUMERIC, VALUE_TOO_LONG, STORAGE_UNAVAILABLE, and PERSISTENCE_FAILED. A convenience success accessor treats SUCCESS and NO_CHANGE as successful operations.
- Subscription: AutoCloseable with idempotent close.

Do not add an extensible result hierarchy, arbitrary payload system, or additional managers.

## Persistence, numeric behavior, and notifications

All authoritative stored reads and mutations run on the existing single storage executor. Read, numeric calculation, write, cache update, and notification remain serialized. Concurrent additions cannot lose updates within this plugin. Writes complete only after SQLite has committed; preserve the existing connection's auto-commit behavior, with explicit verification rather than assuming an uncommitted executeUpdate is sufficient.

Add uses BigDecimal and the existing plain-string formatting. If no row exists, use the configured default. Reject absent or nonnumeric starting values; never convert them to zero in the API. Preserve the command's existing zero-start behavior when neither a stored value nor default exists. Avoid changing command messages, permissions, or syntax.

Identical raw values and deletion of an absent row require no write and return NO_CHANGE. A raw override transition can still be meaningful for future defaults, so persist requested set/reset operations even when the current configured default makes the resolved value identical. Return SUCCESS for that persisted transition but suppress its notification when the effective stored value, defined as raw override or configured default, is unchanged. Notification old/new fields remain raw overrides; explain this distinction in API documentation and tests. This avoids notifying for reset-to-default or materializing a default without losing the caller's intended stored state.

Notify exactly once per effective change, after the committed write and cache update, including mutations initiated by existing commands. Failed reads/writes, rejected operations, and no-op effective changes emit nothing. A failed write leaves caches unchanged. Subscriber failures must not turn a committed mutation into a failed operation or prevent other subscribers from running. Retain bounded, redacted subscriber diagnostics.

Callbacks run on the storage executor before mutation completion. They must remain short and must not block waiting for another storage future. Consumers schedule Bukkit work onto the server thread. CompletableFuture continuations have no server-thread guarantee.

## Subscription ownership and shutdown

Offer `subscribe(Plugin owner, Consumer<VariableChange> listener)` in addition to explicitly managed `subscribe(listener)`. Reject registration for an already disabled owner or a shutting-down provider. An internal Bukkit PluginDisableEvent listener closes and removes registrations for the disabled owner. Closing a handle releases listener and owner references, including references retained through a handle that the consumer keeps. Owner validation and registration must be safe against disable/registration races; owner registration is a server-thread operation.

Use a small internal registration representation with an active flag and clearable references, rather than a new event framework. Close prevents later callback entry; a callback already running may finish. Closing from within a callback must be safe. Unregister the provider's Bukkit lifecycle listener and clear all subscriptions when PlexVariables disables. Keep the existing bounded storage drain and shutdown policy; reject new work cleanly during shutdown.

## Compatibility and release documentation

Set Gradle project version to 1.0.1. plugin.yml already expands the Gradle version; verify the packaged descriptor reports 1.0.1. Update README and docs/API.md with ExamplePlugin examples for discovery, resolve, reads, all mutations, contexts, results, ownership, unsubscribe, and threading.

Add a `## 1.0.1` changelog section covering actual final changes and actual validation. Preserve historical 1.0.0 release information. Remove prohibited consumer naming from all project documentation, including development documents. Do not copy consumer names into this spec or the future plan. No configuration or database migration is required.

## Verification and acceptance

First obtain a fresh baseline using the existing workspace-local Gradle cache and Java 21. The initial sandbox attempt failed connecting to the daemon, so use an approved execution context allowing local daemon communication; do not change project JVM settings merely to hide an environment restriction.

Extend real SQLite-backed tests and existing command/resolver tests. Cover both scopes; reads of present and absent rows; set/add/reset; integer and decimal arithmetic; defaults; missing/nonnumeric starting values; value limits; wrong scopes and invalid inputs; context propagation and context-free overloads; concurrent adds; old/new raw values; raw and effective no-ops; successful persistence visibility; cache integrity and no notifications after a forced real database write failure; subscriber isolation; unsubscribe; owner disable cleanup; provider shutdown; restart persistence; and unchanged schema version/tables.

Test Bukkit service registration/discovery and unregistration with the existing Mockito setup, exercising actual lifecycle wiring where feasible. Distinguish these automated lifecycle checks from a live Paper smoke test; never claim a live server test unless one was performed. Preserve and run existing command, configuration, stored-variable, and placeholder-resolution regression coverage.

Run a fresh `gradlew clean test build`, using only necessary environment flags. Derive total tests, failures, errors, and skips from freshly generated XML. Inspect the shaded JAR version and dependency packaging. Run diff whitespace checks, dependency review, and a scan for newly introduced source line comments. Search all project source, configuration, build files, tests, and documents case-insensitively for the prohibited consumer name, including hidden project files but excluding Git internals and third-party Gradle caches; separately inspect generated project artifacts. Required final match count is zero. Historical Git objects are not edited.

The final report lists files changed/created, contracts, mutation and subscription behavior, version, compatibility, actual tests/build outcomes, migration status, and any unperformed live-server validation. No completion or zero-reference claim is made before those checks finish.

## Workflow status

The overall design is approved. This written specification awaits user review. After approval, create the detailed implementation plan and obtain the required plan review/execution selection, then implement and verify the changes.
