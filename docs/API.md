# Public Java API — PlexVariables 1.0.1

PlexVariables 1.0.1 introduces `dev.plex.plexvariables.api.PlexVariablesApi` (API version `1`). Any Paper plugin can consume this service. The public package contains one service interface and its nested immutable records, enums, subscription interface, and read exception. Consumers do not need a main-plugin cast, static internals, or implementation imports.

## Dependency and discovery

Compile against the 1.0.1 shaded plugin JAR without bundling it into your plugin. For a local Gradle dependency, place the real built JAR at the path you declare:

```groovy
dependencies {
    compileOnly files('libs/PlexVariables-1.0.1.jar')
}
```

For a required dependency, add `depend: [PlexVariables]` to your own plugin.yml. For an optional integration, use `softdepend: [PlexVariables]`, handle service absence, and avoid loading API-dependent classes until the dependency is available. PlexVariables does not declare dependencies on its consumers.

Obtain the service during your plugin's enable lifecycle:

```java
PlexVariablesApi api = getServer().getServicesManager().load(PlexVariablesApi.class);
if (api == null) {
    getLogger().severe("PlexVariables API is unavailable");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

The service is registered after successful PlexVariables initialization and unregistered on disable. Do not retain a provider across PlexVariables disable/re-enable; reacquire it for a new integration lifecycle.

## Example variable configuration

The operations below assume these example definitions are added by the server administrator to a variable YAML file. These are documentation examples, not data automatically created by the API:

```yaml
variables:
  score:
    type: stored
    scope: player
    default: "0"
  total:
    type: stored
    scope: global
    default: "0"
```

IDs are case insensitive. Operations validate the current definition and scope before submission. A mutation uses the validated definition's default snapshot even if configuration is reloaded while that mutation waits in the queue.

## Resolving and reading

`variable(id)` returns an Optional containing immutable definition metadata, or an empty Optional for an unknown ID. A non-stored definition has a null metadata scope. `resolve(player, id)` uses the existing resolver, nested placeholders, formatting, and defaults. It requires the server thread and returns null for an unknown ID. A null player retains the resolver's existing context-free behavior.

```java
String resolved = api.resolve(player, "score");
CompletableFuture<Optional<String>> playerOverride =
        api.getStoredPlayerValue(player.getUniqueId(), "score");
CompletableFuture<Optional<String>> globalOverride =
        api.getStoredGlobalValue("total");
```

Resolution reads caches and never waits for SQLite. Authoritative reads execute on the storage executor, including for offline UUIDs, and return raw persisted overrides. An absent row is `Optional.empty()`, even when a configured default exists. Global reads need no player or special UUID. Player reads require a real player UUID; the API never performs name lookups.

## Set, add, and reset

Every mutation returns `CompletableFuture<PlexVariablesApi.MutationResult>`:

```java
UUID playerId = player.getUniqueId();
CompletableFuture<PlexVariablesApi.MutationResult> playerSet =
        api.setStoredPlayerValue(playerId, "score", "20");
CompletableFuture<PlexVariablesApi.MutationResult> playerAdd =
        api.addStoredPlayerValue(playerId, "score", new BigDecimal("2.5"));
CompletableFuture<PlexVariablesApi.MutationResult> playerReset =
        api.resetStoredPlayerValue(playerId, "score");
CompletableFuture<PlexVariablesApi.MutationResult> globalSet =
        api.setStoredGlobalValue("total", "20");
CompletableFuture<PlexVariablesApi.MutationResult> globalAdd =
        api.addStoredGlobalValue("total", BigDecimal.ONE);
CompletableFuture<PlexVariablesApi.MutationResult> globalReset =
        api.resetStoredGlobalValue("total");
```

These calls illustrate the available operations; choose the operations your plugin needs. Submitted mutations are serialized. Set accepts any nonnull string within the configured storage length limit. Reset removes the override so normal resolution can use the configured default. Add uses BigDecimal, accepts integer/decimal/negative amounts, and writes normalized plain decimal strings without exponent notation. It starts from the persisted value or configured default. A missing start without a default produces MISSING_VALUE; invalid numeric text produces NON_NUMERIC. It never silently starts at zero. Existing `/pv add` retains its established zero-start behavior when no default is configured.

## Optional mutation context

All six mutation methods have an overload appending MutationContext. Simple calls above require no context. A context-taking overload requires a nonnull context:

```java
var context = new PlexVariablesApi.MutationContext(
        "ExamplePlugin", Optional.of("reward-operation"), Map.of("action", "reward"));
api.addStoredPlayerValue(player.getUniqueId(), "score", BigDecimal.ONE, context);
```

`new MutationContext("ExamplePlugin")` is sufficient when only a source is useful. Source must be nonblank. Correlation IDs and metadata are opaque, optional caller information; source names are never interpreted. Metadata is defensively copied into an immutable string map, rejecting null keys/values. No caller-owned mutable payload is retained in the public models.

## Results and errors

| Status | Meaning |
| --- | --- |
| SUCCESS | The requested raw override transition committed successfully. |
| NO_CHANGE | The requested raw state already existed; no database write was needed. |
| MISSING_VALUE | Numeric add had neither a persisted value nor a configured default. |
| NON_NUMERIC | The persisted starting value or configured default could not be parsed numerically. |
| VALUE_TOO_LONG | The resulting value exceeded the configured storage limit. |
| STORAGE_UNAVAILABLE | Storage is shutting down or no longer accepts work. |
| PERSISTENCE_FAILED | A stored read or write failed during mutation. |

`result.success()` is true for SUCCESS and NO_CHANGE. Those statuses include `result.change()` with raw old/new values. Failure statuses contain no change record, rather than presenting unknown state as an absent database row. Model constructors validate these invariants.

```java
api.addStoredGlobalValue("total", BigDecimal.ONE).thenAccept(result -> {
    if (!result.success()) {
        getLogger().warning("Global add failed: " + result.status());
        return;
    }
    result.change().orElseThrow().newValue().ifPresent(value ->
            getLogger().info("Stored total is now " + value));
});
```

Invalid arguments (null required fields, blank IDs, unknown/non-stored variables, wrong scope) throw argument exceptions synchronously. Check those before submitting user-supplied requests. Reads complete exceptionally with `PlexVariablesApi.ReadException`, whose `status()` is STORAGE_UNAVAILABLE or PERSISTENCE_FAILED. Internal storage exceptions and database details are not part of this public contract. Handle unexpected exceptional completion as well; no continuation is guaranteed to run on the server thread.

## Raw state versus effective changes

Change records contain variable ID, explicit scope, Optional player UUID (present only for PLAYER), Optional old/new raw overrides, and Optional context. Empty raw values mean absent rows, not configured defaults. Reset therefore has an empty new override.

Identical raw writes and reset of an absent row return NO_CHANGE and do not notify. Setting an override equal to its configured default still persists that override and returns SUCCESS: its raw state affects future default changes. Resetting that same override also returns SUCCESS. Neither emits a notification when the current effective stored value stays the same. Effective comparison uses the raw override or configured default as text; numeric strings `1.0` and `1` are distinct because variable resolution preserves text. Placeholder expansion itself is not monitored, and configuration reloads do not generate stored-mutation notifications.

## Listening, ownership, and unsubscribe

```java
PlexVariablesApi.Subscription subscription = api.subscribe(this, change -> {
    getLogger().info("Stored variable changed: " + change.variable());
});
subscription.close();
```

Owner-aware registration must run on the server thread and requires an enabled Bukkit Plugin owner. PlexVariables removes that owner's subscriptions on PluginDisableEvent and clears all subscriptions on its own shutdown. Closing a handle is idempotent and releases listener/owner references even if the consumer retains the handle. A callback already acquired for execution may finish; closing prevents subsequent callback entry. A listener can close itself or another subscription safely.

For an explicitly managed subscription use `api.subscribe(listener)` and close it when your integration ends. Provider shutdown also clears these registrations. New subscriptions are rejected during shutdown. Subscriber failures do not affect committed writes or other subscribers; only the first failure per registration is logged, using its class without its message or stack trace.

Callbacks run on the **storage executor**, after successful SQLite commit and cache update and before the mutation future completes. Existing commands use the same notification path. Failed operations and effective no-ops do not notify. Keep callbacks short. Never wait for another storage future from a callback: it would wait on the same executor. Schedule Bukkit world/player work with your plugin's scheduler instead. Do not block the server thread with join/get on storage futures.

## Complete ExamplePlugin integration

This example grants one score point when a player joins, using the example `score` definition above. It handles mutation results asynchronously and owns its subscription explicitly:

```java
package example;

import dev.plex.plexvariables.api.PlexVariablesApi;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;

public final class ExamplePlugin extends JavaPlugin implements Listener {
    private PlexVariablesApi api;
    private PlexVariablesApi.Subscription subscription;

    @Override
    public void onEnable() {
        api = getServer().getServicesManager().load(PlexVariablesApi.class);
        if (api == null || api.variable("score").isEmpty()) {
            getLogger().severe("PlexVariables API or score definition is unavailable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        subscription = api.subscribe(this, change ->
                getLogger().info("Stored variable changed: " + change.variable()));
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        api.addStoredPlayerValue(event.getPlayer().getUniqueId(), "score", BigDecimal.ONE)
                .thenAccept(result -> {
                    if (!result.success()) getLogger().warning("Score add failed: " + result.status());
                }).exceptionally(failure -> {
                    getLogger().warning("Score add could not complete");
                    return null;
                });
    }

    @Override
    public void onDisable() {
        if (subscription != null) subscription.close();
        subscription = null;
        api = null;
    }
}
```

## Compatibility and verification

No configuration or database migration is required. Existing commands, permissions, placeholders, and variable behavior remain available. Pending storage work drains under the existing bounded shutdown timeout; new work is rejected during shutdown. Tests use real temporary SQLite databases for persistence, failure triggers, concurrent additions, restart, and schema checks. Automated lifecycle tests use the real Bukkit SimpleServicesManager with mocked event plumbing. A live Paper integration test is a separate check and has not been performed for this change.