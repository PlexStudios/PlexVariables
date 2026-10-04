# PlexVariables v1.0.0 Acceptance Testing Guide

This document provides a clean-install acceptance testing protocol for verifying **PlexVariables v1.0.0** on a live Paper Minecraft server.

---

## Prerequisites

- Java 21 JDK
- Paper 1.21+ server instance
- PlaceholderAPI 2.11.6+ installed in `plugins/`
- `PlexVariables-1.0.0.jar` placed in `plugins/`

---

## Test Protocol

### A. First-Install & File Seeding
1. Start the server with `PlexVariables-1.0.0.jar` present.
2. Confirm the server log displays: `[PlexVariables] Loaded X variables from Y files...` and `Registered PlaceholderAPI expansion: %plexvar_<variable>%`.
3. Check `plugins/PlexVariables/`:
   - `config.yml`
   - `messages.yml`
   - `data.db` (SQLite database created)
   - `variables/general.yml`
   - `variables/examples.yml`
   - `variables/conditions.yml`
   - `variables/expressions.yml`
   - `variables/storage.yml`

### B. Static Variables
1. Run `/pv parse --null %plexvar_server_name%`.
2. Expected output: Resolves static text (e.g. `PlexNetwork`).

### C. Nested & Cross-File Variables
1. Run `/pv parse --null %plexvar_welcome_banner%`.
2. Expected output: Resolves nested PlexVariables and color codes without double-colorization or text duplication.

### D. Conditional Variables
1. As a player without op, run `/pv parse <your-name> %plexvar_rank_badge%`. Expected: `&7[Member]`.
2. Grant permission `group.vip` or op, run `/pv parse <your-name> %plexvar_rank_badge%`. Expected: `&a[VIP]`.
3. Run `/pv test <your-name> rank_badge`. Verify trace output displays condition matching step by step.

### E. Expression Variables
1. Run `/pv parse <your-name> %plexvar_simple_math%`. Expected: `14`.
2. Run `/pv parse <your-name> %plexvar_kd_ratio%`. Expected: Calculated numeric ratio formatted to 2 decimals.
3. Run `/pv test --null div_zero_fallback`. Expected: Shows division by zero fallback to per-variable `on-error` value.

### F. Stored Player Variables
1. Run `/pv set player_gems <your-name> 250`. Expected message: `Set player stored variable 'player_gems' for <your-name> to '250'`.
2. Run `/pv parse <your-name> %plexvar_player_gems%`. Expected: `250`.
3. Run `/pv add player_gems <your-name> 50`. Expected message: `Added 50 to player stored variable 'player_gems' for <your-name>. New value: '300'`.
4. Run `/pv get player_gems <your-name>`. Expected: `Stored value: 300 (Default: 0)`.

### G. Stored Global Variables
1. Run `/pv set server_event_status global active`. Expected message: `Set global stored variable 'server_event_status' for global to 'active'`.
2. Run `/pv parse --null %plexvar_server_event_status%`. Expected: `active`.
3. Run `/pv parse --null %plexvar_global_server_event_status%`. Expected: `active` (global alias).

### H. SQLite Persistence
1. Stop the server (`stop`).
2. Restart the server.
3. Run `/pv get player_gems <your-name>` and `/pv get server_event_status global`.
4. Expected: Both stored values (`300` and `active`) persist intact across restart.
5. Run `/pv reset player_gems <your-name>`. Expected: Removes explicit DB entry, reverting to default (`0`).

### I. Command Permissions & Tab Completion
1. Test as non-op player without permissions: `/pv reload` -> `No permission`.
2. Verify tab completion suggests valid subcommands, online players, and stored variable IDs.

### J. Transactional Reload & Rollback
1. Edit `plugins/PlexVariables/config.yml` with invalid YAML syntax.
2. Run `/pv reload`. Expected: Red error message indicating reload failure; previously loaded snapshot remains active and placeholders continue resolving.
3. Fix `config.yml` syntax and run `/pv reload`. Expected: Green success message.

### K. Cycle Protection
1. Create a variable cycle: `var_a: "%plexvar_var_b%"` and `var_b: "%plexvar_var_a%"`.
2. Run `/pv parse --null %plexvar_var_a%`.
3. Expected: Returns configured `error-value` (default: `N/A`) with single throttled console warning, no stack overflow or thread lockup.

### L. PlaceholderAPI Reload
1. Run `/papi reload`.
2. Run `/papi parse <your-name> %plexvar_server_name%`.
3. Expected: `%plexvar%` expansion remains registered and functioning.

### M. Clean Shutdown
1. Stop the server (`stop`).
2. Verify console logs show clean plugin shutdown without dangling threads, SQLite exceptions, or unclosed connection warnings.
