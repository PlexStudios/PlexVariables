# PlexVariables Developer API

**1.0.1 beta / pre-release:** Automated validation is complete; manual Paper testing is still pending.

PlexVariables 1.0.1 introduces a public API for Paper plugins that need to resolve variables, read or modify stored values, and react to stored-variable changes without running PlexVariables commands or accessing internal classes.

The API is exposed through Bukkit's `ServicesManager` and is designed to remain independent from any specific consumer plugin.

## Requirements

- PlexVariables 1.0.1+
- Paper 1.21+
- Java 21
- PlaceholderAPI installed on the server

Your plugin should treat PlexVariables as a runtime dependency or optional integration, depending on whether your plugin can operate without it.

## Adding PlexVariables to Your Project

PlexVariables does not currently publish an official Maven repository artifact.

For local development, place the PlexVariables 1.0.1 JAR in your project's `libs/` directory and use it as a compile-only dependency.

### Gradle

```gradle
dependencies {
    compileOnly files('libs/PlexVariables-1.0.1.jar')
}
```

Do not shade or bundle PlexVariables inside your plugin.

## plugin.yml

If your plugin requires PlexVariables:

```yaml
depend:
  - PlexVariables
```

If PlexVariables support is optional:

```yaml
softdepend:
  - PlexVariables
```

## Obtaining the API

PlexVariables publishes its API through Bukkit's `ServicesManager`.

```java
import dev.plex.plexvariables.api.PlexVariablesApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<PlexVariablesApi> registration =
        Bukkit.getServicesManager().getRegistration(PlexVariablesApi.class);

if (registration == null) {
    return;
}

PlexVariablesApi api = registration.getProvider();
```

Do not cast the PlexVariables main plugin class and do not access storage, cache, resolver, or SQLite implementation classes directly.

## Variable Definitions

Use `variable(String)` to look up public metadata for a configured PlexVariable.

```java
var variable = api.variable("player_level");

if (variable.isEmpty()) {
    return;
}

var info = variable.get();
```

Unknown IDs return an empty `Optional`.

Definition metadata is an immutable snapshot and does not expose PlexVariables internals.

## Resolving a Variable

Use the normal resolver when you want the same resolved value that PlexVariables would expose through PlaceholderAPI.

```java
var value = api.resolve(player, "health_status");
```

Resolution follows normal PlexVariables behavior, including defaults, formatting, nested variables, and PlaceholderAPI expansion.

Variable resolution is a server-thread operation. Do not call it from an arbitrary asynchronous thread.

## Stored Values

Stored-variable reads return the raw persisted override.

They do not automatically substitute the configured default.

This distinction matters:

- No persisted row: `Optional.empty()`
- Persisted value `"100"`: `Optional.of("100")`
- Configured default `"100"` with no row: still `Optional.empty()`

Use normal variable resolution when you want the final effective value after defaults and formatting.

## Reading Player Values

```java
api.getStoredPlayerValue(
        player.getUniqueId(),
        "gems"
).thenAccept(value -> {
    value.ifPresent(System.out::println);
});
```

Return type:

```java
CompletableFuture<Optional<String>>
```

## Reading Global Values

```java
api.getStoredGlobalValue("season")
        .thenAccept(value -> {
            value.ifPresent(System.out::println);
        });
```

Reads are asynchronous and use PlexVariables' storage executor.

## Setting Player Values

```java
api.setStoredPlayerValue(
        player.getUniqueId(),
        "gems",
        "250"
).thenAccept(result -> {
    if (!result.success()) {
        getLogger().warning("Update failed: " + result.status());
    }
});
```

## Setting Global Values

```java
api.setStoredGlobalValue(
        "season",
        "3"
).thenAccept(result -> {
    if (!result.success()) {
        getLogger().warning("Update failed: " + result.status());
    }
});
```

## Adding Numeric Values

Numeric additions use `BigDecimal`.

### Player

```java
import java.math.BigDecimal;

api.addStoredPlayerValue(
        player.getUniqueId(),
        "gems",
        new BigDecimal("100")
);
```

### Global

```java
api.addStoredGlobalValue(
        "total_votes",
        BigDecimal.ONE
);
```

The API does not silently convert missing or non-numeric starting values to zero.

If the current stored value or configured starting value cannot be used numerically, the operation returns an appropriate mutation status.

## Resetting Values

Reset removes the persisted override and allows the configured default to become effective again.

### Player

```java
api.resetStoredPlayerValue(
        player.getUniqueId(),
        "gems"
);
```

### Global

```java
api.resetStoredGlobalValue("season");
```

## Mutation Results

Set, add, and reset operations return:

```java
CompletableFuture<PlexVariablesApi.MutationResult>
```

A mutation result contains:

- `status()`
- `change()`
- `success()`

`success()` returns `true` for both `SUCCESS` and `NO_CHANGE`.

### Status Values

| Status | Meaning |
| --- | --- |
| `SUCCESS` | The requested persisted state change completed successfully. |
| `NO_CHANGE` | The requested operation did not change the raw persisted state. |
| `MISSING_VALUE` | A numeric operation had neither a stored override nor a configured starting value. |
| `NON_NUMERIC` | A numeric operation encountered a non-numeric value. |
| `VALUE_TOO_LONG` | The requested value exceeded the configured storage limit. |
| `STORAGE_UNAVAILABLE` | Storage could not accept the operation. |
| `PERSISTENCE_FAILED` | The database operation failed. |

Example:

```java
api.addStoredPlayerValue(
        player.getUniqueId(),
        "gems",
        BigDecimal.TEN
).thenAccept(result -> {
    switch (result.status()) {
        case SUCCESS, NO_CHANGE ->
                getLogger().info("Mutation completed.");
        default ->
                getLogger().warning("Mutation failed: " + result.status());
    }
});
```

## Mutation Context

Mutation context is optional.

Normal integrations do not need to provide one.

Use it when your plugin wants to attach generic source or correlation information to a mutation.

```java
PlexVariablesApi.MutationContext context =
        new PlexVariablesApi.MutationContext("ExamplePlugin");
```

Then pass it as the final argument:

```java
api.setStoredPlayerValue(
        player.getUniqueId(),
        "gems",
        "500",
        context
);
```

A context may contain:

- a nonblank source
- an optional correlation ID
- immutable string metadata

PlexVariables carries this data without interpreting the source or metadata.

Example with additional metadata:

```java
import java.util.Map;
import java.util.Optional;

PlexVariablesApi.MutationContext context =
        new PlexVariablesApi.MutationContext(
                "ExamplePlugin",
                Optional.of("reward-481"),
                Map.of("reason", "daily-reward")
        );
```

## Variable Changes

Successful effective changes can be observed through subscriptions.

A `VariableChange` provides:

- variable ID
- scope
- optional player UUID
- old raw persisted override
- new raw persisted override
- optional mutation context

Player-scoped changes include a player UUID.

Global changes do not.

## Owner-Aware Subscriptions

For most plugins, use the owner-aware subscription method.

```java
PlexVariablesApi.Subscription subscription =
        api.subscribe(this, change -> {
            getLogger().info(
                    change.variable()
                            + " changed from "
                            + change.oldValue().orElse("<unset>")
                            + " to "
                            + change.newValue().orElse("<unset>")
            );
        });
```

PlexVariables automatically removes owner-aware subscriptions when the owning plugin disables.

Owner-aware registration is a server-thread operation and the owner must currently be enabled.

You may still close the returned handle manually:

```java
subscription.close();
```

Closing is idempotent.

## Manually Managed Subscriptions

A subscription can also be created without an owning plugin:

```java
PlexVariablesApi.Subscription subscription =
        api.subscribe(change -> {
            getLogger().info(change.variable());
        });
```

You are responsible for closing manually managed subscriptions:

```java
subscription.close();
```

Do not leave unmanaged subscriptions active after your plugin disables.

## Notification Semantics

PlexVariables distinguishes between raw persisted state and the final effective value.

For example, assume:

```yaml
default: "10"
```

If no database row exists and your plugin explicitly stores `"10"`:

- The raw persisted state changed.
- The mutation can return `SUCCESS`.
- The effective value remained `"10"`.
- No change notification is emitted.

Likewise, resetting an override back to the same configured default can successfully change persisted state without producing an effective-value notification.

This prevents consumers from receiving misleading change events when the value seen by players did not actually change.

Repeated operations that do not change the raw persisted state return `NO_CHANGE`.

## Notification Timing

Change subscribers are notified only after:

1. The authoritative stored value has been read.
2. The mutation has been calculated and validated.
3. SQLite has successfully committed the write.
4. PlexVariables has updated its relevant cache.
5. The effective stored value is confirmed to have changed.

Failed writes do not update caches and do not emit successful change notifications.

Subscriber failures do not turn an already committed mutation into a failed mutation and do not prevent other subscribers from receiving the change.

## Threading

Stored reads and mutations are asynchronous.

Do not block the Paper server thread waiting for them.

Avoid:

```java
api.getStoredGlobalValue("season").join();
```

Prefer asynchronous composition:

```java
api.getStoredGlobalValue("season")
        .thenAccept(value -> {
            getLogger().info(value.orElse("<unset>"));
        });
```

Subscription callbacks execute on PlexVariables' storage executor.

They are not Paper server-thread callbacks.

Do not directly modify players, worlds, inventories, entities, or other thread-sensitive Bukkit state from a subscription callback.

Schedule Bukkit work back onto the server thread:

```java
api.subscribe(this, change -> {
    getServer().getScheduler().runTask(this, () -> {
        getLogger().info("Handling " + change.variable() + " on the server thread.");
    });
});
```

Callbacks should remain short.

Do not block a subscription callback waiting for another PlexVariables storage future, because stored operations use the same serialized storage executor.

`CompletableFuture` continuations also have no automatic server-thread guarantee.

## Read Failures

Persisted reads complete exceptionally when storage cannot perform the read.

The public API exposes `PlexVariablesApi.ReadException`, with `STORAGE_UNAVAILABLE` or `PERSISTENCE_FAILED` available through `status()`, rather than leaking internal SQLite or storage exceptions.

Example:

```java
api.getStoredGlobalValue("season")
        .whenComplete((value, throwable) -> {
            if (throwable != null) {
                getLogger().warning("Could not read season.");
                return;
            }

            getLogger().info(value.orElse("<unset>"));
        });
```

Do not depend on PlexVariables' internal storage exception types.

## Player and Global Scope

The API uses explicit methods for each scope.

Use:

```text
getStoredPlayerValue
setStoredPlayerValue
addStoredPlayerValue
resetStoredPlayerValue
```

for player-scoped variables.

Use:

```text
getStoredGlobalValue
setStoredGlobalValue
addStoredGlobalValue
resetStoredGlobalValue
```

for global variables.

Unknown or non-stored definitions and scope mismatches throw `IllegalArgumentException` synchronously. Required null arguments throw `NullPointerException`. Use context-free overloads when no mutation context is needed; context-taking overloads require a non-null context.

Do not use fake UUIDs or `null` players to represent global values.

## Complete Example

Call ewardPlayer on the server thread. Configure a player-scoped stored variable named gems with a numeric default before using this example; the API never creates definitions implicitly.

```java
package com.example.exampleplugin;

import dev.plex.plexvariables.api.PlexVariablesApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;

public final class ExamplePlugin extends JavaPlugin {
    private PlexVariablesApi plexVariablesApi;
    private PlexVariablesApi.Subscription subscription;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<PlexVariablesApi> registration =
                Bukkit.getServicesManager().getRegistration(PlexVariablesApi.class);

        if (registration == null) {
            getLogger().warning("PlexVariables 1.0.1+ was not found.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        plexVariablesApi = registration.getProvider();

        subscription = plexVariablesApi.subscribe(this, change -> {
            getLogger().info(
                    change.variable()
                            + ": "
                            + change.oldValue().orElse("<unset>")
                            + " -> "
                            + change.newValue().orElse("<unset>")
            );
        });
    }

    public void rewardPlayer(org.bukkit.entity.Player player) {
        java.util.UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        plexVariablesApi.addStoredPlayerValue(
                playerId,
                "gems",
                new BigDecimal("100")
        ).thenAccept(result -> {
            if (!result.success()) {
                getLogger().warning(
                        "Could not reward "
                                + playerName
                                + ": "
                                + result.status()
                );
            }
        });
    }

    @Override
    public void onDisable() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
```

Owner-aware subscriptions are automatically removed when the plugin disables, but explicitly closing a retained handle is still safe.

## Provider Lifecycle

New work is rejected during provider shutdown. Pending storage work drains within the existing bounded shutdown timeout. If that timeout expires, queued operations that never started complete with `STORAGE_UNAVAILABLE` (a mutation result, or `ReadException` for reads). An already committed mutation retains its successful result.

Owner subscriptions cannot be recreated during the owner's disable event. Provider shutdown closes all subscriptions. Closing a subscription releases retained owner and callback references; a callback already acquired for delivery may finish. Reacquire the service and register fresh subscriptions after the provider is enabled again.

## Compatibility

PlexVariables 1.0.1 keeps the existing:

- variable configuration format
- PlaceholderAPI placeholders
- commands and aliases
- permissions
- player/global stored data
- SQLite schema

No database or configuration migration is required from PlexVariables 1.0.0.

## API Stability

The public API was introduced in PlexVariables 1.0.1.

Plugins should depend only on types exposed through `dev.plex.plexvariables.api` and obtain the service through Bukkit's `ServicesManager`.

Do not depend on implementation packages such as:

```text
dev.plex.plexvariables.implementation
dev.plex.plexvariables.storage
dev.plex.plexvariables.variable
dev.plex.plexvariables.config
```

Internal implementation packages may change independently of the public API.

## Quick Reference

| Operation | Method |
| --- | --- |
| Find variable metadata | `variable(String)` |
| Resolve variable | `resolve(OfflinePlayer, String)` |
| Read player override | `getStoredPlayerValue(UUID, String)` |
| Read global override | `getStoredGlobalValue(String)` |
| Set player value | `setStoredPlayerValue(UUID, String, String)` |
| Set global value | `setStoredGlobalValue(String, String)` |
| Add player value | `addStoredPlayerValue(UUID, String, BigDecimal)` |
| Add global value | `addStoredGlobalValue(String, BigDecimal)` |
| Reset player value | `resetStoredPlayerValue(UUID, String)` |
| Reset global value | `resetStoredGlobalValue(String)` |
| Subscribe with owner | `subscribe(Plugin, Consumer<VariableChange>)` |
| Subscribe manually | `subscribe(Consumer<VariableChange>)` |

All stored mutations also provide overloads accepting `MutationContext`.

## Support

When reporting an API issue, include:

- PlexVariables version
- Paper version
- Java version
- the relevant API method
- the returned mutation status or exception
- a minimal reproduction if possible

Do not include private server credentials, database files, tokens, or unrelated sensitive data.
