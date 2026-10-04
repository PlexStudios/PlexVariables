package dev.plex.plexvariables.command;

import dev.plex.plexvariables.condition.ConditionType;
import dev.plex.plexvariables.condition.EvaluationTrace;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.config.ReloadService;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.storage.StoredVariableScope;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableResolver;
import dev.plex.plexvariables.variable.VariableType;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class PlexVariablesCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("help", "reload", "list", "parse", "test", "set", "add", "get", "reset", "remove");
    private final Supplier<PluginState> state;
    private final VariableResolver resolver;
    private final ReloadService reloadService;
    private final StorageManager storageManager;
    private final Server server;
    private final Logger logger;

    public PlexVariablesCommand(Supplier<PluginState> state, VariableResolver resolver,
                                 ReloadService reloadService, StorageManager storageManager,
                                 Server server, Logger logger) {
        this.state = state;
        this.resolver = resolver;
        this.reloadService = reloadService;
        this.storageManager = storageManager;
        this.server = server;
        this.logger = logger;
    }

    public PlexVariablesCommand(Supplier<PluginState> state, VariableResolver resolver,
                                 ReloadService reloadService, Server server, Logger logger) {
        this(state, resolver, reloadService, null, server, logger);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        PluginState current = state.get();
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed(sender, action)) {
            current.messages().send(sender, "no-permission");
            return true;
        }
        switch (action) {
            case "help" -> current.messages().send(sender, "help", Map.of("label", label));
            case "reload" -> reloadService.reload(sender);
            case "list" -> list(sender, current, label, args);
            case "parse" -> parse(sender, current, label, args);
            case "test" -> test(sender, current, label, args);
            case "set" -> set(sender, current, label, args);
            case "add" -> add(sender, current, label, args);
            case "get" -> get(sender, current, label, args);
            case "reset", "remove" -> reset(sender, current, label, args);
            default -> current.messages().send(sender, "unknown-command", Map.of("label", label));
        }
        return true;
    }

    private void list(CommandSender sender, PluginState current, String label, String[] args) {
        int size = current.settings().listPageSize();
        int pages = Math.max(1, (current.variables().size() + size - 1) / size);
        int page = 1;
        try {
            if (args.length > 2) {
                throw new NumberFormatException();
            }
            if (args.length == 2) {
                page = Integer.parseInt(args[1]);
            }
            if (page < 1 || page > pages) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            current.messages().send(sender, "invalid-page", Map.of("pages", Integer.toString(pages)));
            return;
        }
        if (current.variables().isEmpty()) {
            current.messages().send(sender, "list-empty");
            return;
        }
        var replacements = Map.of("variables", Integer.toString(current.variables().size()),
                "page", Integer.toString(page), "pages", Integer.toString(pages), "label", label);
        current.messages().send(sender, "list-header", replacements);
        current.variables().values().stream().sorted(Comparator.comparing(VariableDefinition::id))
                .skip((long) (page - 1) * size).limit(size)
                .forEach(variable -> current.messages().send(sender, "list-entry", Map.of(
                        "variable", variable.id(),
                        "type", variable.type().name(),
                        "source", variable.sourceFile())));
        if (pages > 1) {
            current.messages().send(sender, "list-footer", replacements);
        }
    }

    private void parse(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 3) {
            current.messages().send(sender, "parse-usage", Map.of("label", label));
            return;
        }
        OfflinePlayer player = null;
        if (!args[1].equalsIgnoreCase("--null")) {
            player = resolvePlayer(args[1]);
            if (player == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", args[1]));
                return;
            }
        }
        String input = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        long started = System.nanoTime();
        String result;
        try {
            result = resolver.parse(player, input);
        } catch (RuntimeException exception) {
            logger.warning("Debug parse failed for player '" + args[1] + "': "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            current.messages().send(sender, "parse-failed");
            return;
        }
        String elapsed = String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0);
        var replacements = Map.of("placeholder", input, "result", result, "time", elapsed,
                "player", args[1]);
        current.messages().send(sender, "parse-input", replacements);
        current.messages().send(sender, "parse-result", replacements);
        current.messages().send(sender, "parse-time", replacements);
    }

    private void test(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 3) {
            current.messages().send(sender, "test-usage", Map.of("label", label));
            return;
        }
        OfflinePlayer player = null;
        if (!args[1].equalsIgnoreCase("--null")) {
            player = resolvePlayer(args[1]);
            if (player == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", args[1]));
                return;
            }
        }
        String varId = args[2].toLowerCase(Locale.ROOT);
        VariableDefinition def = current.variables().get(varId);
        if (def == null) {
            current.messages().send(sender, "variable-not-found", Map.of("variable", args[2]));
            return;
        }

        EvaluationTrace trace;
        try {
            trace = resolver.test(player, varId);
        } catch (RuntimeException exception) {
            logger.warning("Debug test failed for player '" + args[1] + "' on variable '" + args[2] + "': "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            current.messages().send(sender, "test-failed");
            return;
        }

        if (trace == null) {
            current.messages().send(sender, "variable-not-found", Map.of("variable", args[2]));
            return;
        }

        var headerReplacements = Map.of(
                "variable", def.id(),
                "type", def.type().name(),
                "source", def.sourceFile()
        );
        current.messages().send(sender, "test-header", headerReplacements);

        if (trace.storedScope() != null) {
            current.messages().send(sender, "test-stored-scope", Map.of("scope", trace.storedScope()));
            current.messages().send(sender, "test-stored-cache-state", Map.of("state", trace.cacheState() != null ? trace.cacheState() : ""));
            current.messages().send(sender, "test-stored-raw", Map.of("raw", trace.rawStoredValue() != null ? trace.rawStoredValue() : ""));
            current.messages().send(sender, "test-stored-default", Map.of("default", trace.storedDefault() != null ? trace.storedDefault() : ""));
            current.messages().send(sender, "test-stored-effective", Map.of("effective", trace.effectiveValue() != null ? trace.effectiveValue() : ""));
        } else if (trace.rawExpression() != null) {
            current.messages().send(sender, "test-expression-raw", Map.of("expression", trace.rawExpression()));
            current.messages().send(sender, "test-expression-resolved", Map.of("resolved", trace.resolvedExpression() != null ? trace.resolvedExpression() : ""));
            if (trace.expressionError() != null) {
                current.messages().send(sender, "test-expression-error", Map.of("error", trace.expressionError()));
            } else {
                current.messages().send(sender, "test-expression-calculated", Map.of("calculated", trace.calculatedResult() != null ? trace.calculatedResult() : ""));
                current.messages().send(sender, "test-expression-formatting", Map.of("formatting", trace.formattingSummary() != null ? trace.formattingSummary() : ""));
            }
        } else {
            for (var step : trace.steps()) {
                current.messages().send(sender, "test-condition-title", Map.of("index", Integer.toString(step.index())));
                current.messages().send(sender, "test-condition-raw", Map.of("condition", step.rawCondition()));
                if (step.type() == ConditionType.COMPARISON) {
                    current.messages().send(sender, "test-condition-resolved", Map.of(
                            "left", step.resolvedLeft() != null ? step.resolvedLeft() : "",
                            "operator", step.operatorSymbol() != null ? step.operatorSymbol() : "",
                            "right", step.resolvedRight() != null ? step.resolvedRight() : ""
                    ));
                } else {
                    current.messages().send(sender, "test-condition-native", Map.of(
                            "expression", step.resolvedLeft() != null ? step.resolvedLeft() : ""
                    ));
                }
                current.messages().send(sender, "test-condition-result", Map.of(
                        "result", step.result() ? "&aMATCHED" : "&cNO MATCH"
                ));
            }

            current.messages().send(sender, "test-selected-branch", Map.of(
                    "branch", trace.selectedBranch() != null ? trace.selectedBranch() : "none"
            ));
        }

        String elapsed = String.format(Locale.ROOT, "%.3f", trace.elapsedMs());
        current.messages().send(sender, "test-result", Map.of("result", trace.finalResult() != null ? trace.finalResult() : ""));
        current.messages().send(sender, "test-time", Map.of("time", elapsed));
    }

    private void set(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 4) {
            current.messages().send(sender, "stored-usage-set", Map.of("label", label));
            return;
        }
        if (storageManager == null) {
            current.messages().send(sender, "stored-error", Map.of("error", "Storage manager disabled"));
            return;
        }

        String varId = args[1].toLowerCase(Locale.ROOT);
        VariableDefinition def = current.variables().get(varId);
        if (def == null || def.type() != VariableType.STORED) {
            current.messages().send(sender, "stored-not-stored", Map.of("variable", args[1]));
            return;
        }

        String targetName = args[2];
        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

        if (def.scope() == StoredVariableScope.GLOBAL) {
            if (!targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-global", Map.of("variable", def.id()));
                return;
            }
            storageManager.setGlobalValue(def.id(), value).thenRun(() -> {
                current.messages().send(sender, "stored-set-success", Map.of("variable", def.id(), "target", "global", "value", value));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        } else {
            if (targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-player", Map.of("variable", def.id()));
                return;
            }
            OfflinePlayer targetPlayer = resolvePlayer(targetName);
            if (targetPlayer == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", targetName));
                return;
            }
            String displayTarget = targetPlayer.getName() != null ? targetPlayer.getName() : targetName;
            storageManager.setPlayerValue(targetPlayer.getUniqueId(), def.id(), value).thenRun(() -> {
                current.messages().send(sender, "stored-set-success", Map.of("variable", def.id(), "target", displayTarget, "value", value));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        }
    }

    private void add(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 4) {
            current.messages().send(sender, "stored-usage-add", Map.of("label", label));
            return;
        }
        if (storageManager == null) {
            current.messages().send(sender, "stored-error", Map.of("error", "Storage manager disabled"));
            return;
        }

        String varId = args[1].toLowerCase(Locale.ROOT);
        VariableDefinition def = current.variables().get(varId);
        if (def == null || def.type() != VariableType.STORED) {
            current.messages().send(sender, "stored-not-stored", Map.of("variable", args[1]));
            return;
        }

        BigDecimal delta;
        try {
            delta = new BigDecimal(args[3]);
        } catch (NumberFormatException e) {
            current.messages().send(sender, "stored-non-numeric");
            return;
        }

        String targetName = args[2];

        if (def.scope() == StoredVariableScope.GLOBAL) {
            if (!targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-global", Map.of("variable", def.id()));
                return;
            }
            String currentVal = storageManager.getGlobalValue(def.id());
            if (currentVal == null) currentVal = def.defaultValue() != null ? def.defaultValue() : "0";

            BigDecimal currentNum;
            try {
                currentNum = new BigDecimal(currentVal.trim());
            } catch (NumberFormatException e) {
                current.messages().send(sender, "stored-non-numeric");
                return;
            }

            BigDecimal resultNum = currentNum.add(delta);
            String newValStr = resultNum.stripTrailingZeros().toPlainString();

            storageManager.setGlobalValue(def.id(), newValStr).thenRun(() -> {
                current.messages().send(sender, "stored-add-success", Map.of(
                        "amount", args[3],
                        "variable", def.id(),
                        "target", "global",
                        "value", newValStr
                ));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        } else {
            if (targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-player", Map.of("variable", def.id()));
                return;
            }
            OfflinePlayer targetPlayer = resolvePlayer(targetName);
            if (targetPlayer == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", targetName));
                return;
            }

            String currentVal = storageManager.getPlayerValue(targetPlayer.getUniqueId(), def.id());
            if (currentVal == null) currentVal = def.defaultValue() != null ? def.defaultValue() : "0";

            BigDecimal currentNum;
            try {
                currentNum = new BigDecimal(currentVal.trim());
            } catch (NumberFormatException e) {
                current.messages().send(sender, "stored-non-numeric");
                return;
            }

            BigDecimal resultNum = currentNum.add(delta);
            String newValStr = resultNum.stripTrailingZeros().toPlainString();
            String displayTarget = targetPlayer.getName() != null ? targetPlayer.getName() : targetName;

            storageManager.setPlayerValue(targetPlayer.getUniqueId(), def.id(), newValStr).thenRun(() -> {
                current.messages().send(sender, "stored-add-success", Map.of(
                        "amount", args[3],
                        "variable", def.id(),
                        "target", displayTarget,
                        "value", newValStr
                ));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        }
    }

    private void get(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 2) {
            current.messages().send(sender, "stored-usage-get", Map.of("label", label));
            return;
        }
        if (storageManager == null) {
            current.messages().send(sender, "stored-error", Map.of("error", "Storage manager disabled"));
            return;
        }

        String varId = args[1].toLowerCase(Locale.ROOT);
        VariableDefinition def = current.variables().get(varId);
        if (def == null || def.type() != VariableType.STORED) {
            current.messages().send(sender, "stored-not-stored", Map.of("variable", args[1]));
            return;
        }

        if (def.scope() == StoredVariableScope.GLOBAL) {
            String val = storageManager.getGlobalValue(def.id());
            String effective = val != null ? val : (def.defaultValue() != null ? def.defaultValue() : "");
            current.messages().send(sender, "stored-get-result", Map.of(
                    "variable", def.id(),
                    "target", "global",
                    "value", effective,
                    "default", def.defaultValue() != null ? def.defaultValue() : ""
            ));
        } else {
            String targetName;
            if (args.length >= 3) {
                targetName = args[2];
            } else if (sender instanceof Player playerSender) {
                targetName = playerSender.getName();
            } else {
                current.messages().send(sender, "stored-usage-get", Map.of("label", label));
                return;
            }

            if (targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-player", Map.of("variable", def.id()));
                return;
            }

            OfflinePlayer targetPlayer = resolvePlayer(targetName);
            if (targetPlayer == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", targetName));
                return;
            }

            String displayTarget = targetPlayer.getName() != null ? targetPlayer.getName() : targetName;
            String val = storageManager.getPlayerValue(targetPlayer.getUniqueId(), def.id());
            if (val != null) {
                current.messages().send(sender, "stored-get-result", Map.of(
                        "variable", def.id(),
                        "target", displayTarget,
                        "value", val,
                        "default", def.defaultValue() != null ? def.defaultValue() : ""
                ));
            } else {
                storageManager.fetchPlayerVariablesDirect(targetPlayer.getUniqueId()).thenAccept(map -> {
                    String directVal = map.get(def.id());
                    String effective = directVal != null ? directVal : (def.defaultValue() != null ? def.defaultValue() : "");
                    current.messages().send(sender, "stored-get-result", Map.of(
                            "variable", def.id(),
                            "target", displayTarget,
                            "value", effective,
                            "default", def.defaultValue() != null ? def.defaultValue() : ""
                    ));
                });
            }
        }
    }

    private void reset(CommandSender sender, PluginState current, String label, String[] args) {
        if (args.length < 3) {
            current.messages().send(sender, "stored-usage-reset", Map.of("label", label));
            return;
        }
        if (storageManager == null) {
            current.messages().send(sender, "stored-error", Map.of("error", "Storage manager disabled"));
            return;
        }

        String varId = args[1].toLowerCase(Locale.ROOT);
        VariableDefinition def = current.variables().get(varId);
        if (def == null || def.type() != VariableType.STORED) {
            current.messages().send(sender, "stored-not-stored", Map.of("variable", args[1]));
            return;
        }

        String targetName = args[2];

        if (def.scope() == StoredVariableScope.GLOBAL) {
            if (!targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-global", Map.of("variable", def.id()));
                return;
            }
            storageManager.deleteGlobalValue(def.id()).thenRun(() -> {
                current.messages().send(sender, "stored-reset-success", Map.of("variable", def.id(), "target", "global"));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        } else {
            if (targetName.equalsIgnoreCase("global")) {
                current.messages().send(sender, "stored-scope-mismatch-player", Map.of("variable", def.id()));
                return;
            }
            OfflinePlayer targetPlayer = resolvePlayer(targetName);
            if (targetPlayer == null) {
                current.messages().send(sender, "player-not-found", Map.of("player", targetName));
                return;
            }
            String displayTarget = targetPlayer.getName() != null ? targetPlayer.getName() : targetName;
            storageManager.deletePlayerValue(targetPlayer.getUniqueId(), def.id()).thenRun(() -> {
                current.messages().send(sender, "stored-reset-success", Map.of("variable", def.id(), "target", displayTarget));
            }).exceptionally(ex -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                current.messages().send(sender, "stored-error", Map.of("error", msg));
                return null;
            });
        }
    }

    private OfflinePlayer resolvePlayer(String name) {
        if (server == null) return null;
        OfflinePlayer player = server.getPlayerExact(name);
        if (player == null) {
            player = server.getOfflinePlayerIfCached(name);
        }
        return player;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("plexvariables.use") || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS.stream().filter(action -> allowed(sender, action)).toList(), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!allowed(sender, action)) {
            return List.of();
        }
        if (action.equals("parse")) {
            if (args.length == 2) {
                List<String> players = new ArrayList<>();
                players.add("--null");
                if (server != null) {
                    server.getOnlinePlayers().stream()
                            .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                            .map(Player::getName).forEach(players::add);
                }
                return matching(players, args[1]);
            }
            return matching(state.get().variables().keySet().stream()
                    .map(id -> "%plexvar_" + id + "%").toList(), args[args.length - 1]);
        }
        if (action.equals("test")) {
            if (args.length == 2) {
                List<String> players = new ArrayList<>();
                players.add("--null");
                if (server != null) {
                    server.getOnlinePlayers().stream()
                            .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                            .map(Player::getName).forEach(players::add);
                }
                return matching(players, args[1]);
            }
            if (args.length == 3) {
                return matching(state.get().variables().keySet().stream().toList(), args[2]);
            }
        }
        if (action.equals("set") || action.equals("add") || action.equals("get") || action.equals("reset") || action.equals("remove")) {
            if (args.length == 2) {
                return matching(state.get().variables().values().stream()
                        .filter(v -> v.type() == VariableType.STORED)
                        .map(VariableDefinition::id).toList(), args[1]);
            }
            if (args.length == 3) {
                List<String> targets = new ArrayList<>();
                targets.add("global");
                if (server != null) {
                    server.getOnlinePlayers().stream()
                            .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                            .map(Player::getName).forEach(targets::add);
                }
                return matching(targets, args[2]);
            }
        }
        if (action.equals("list") && args.length == 2) {
            var current = state.get();
            int size = current.settings().listPageSize();
            int pages = Math.max(1, (current.variables().size() + size - 1) / size);
            return java.util.stream.IntStream.rangeClosed(1, pages).mapToObj(Integer::toString)
                    .filter(page -> page.startsWith(args[1])).limit(100).toList();
        }
        return List.of();
    }

    private static boolean allowed(CommandSender sender, String action) {
        return sender.hasPermission("plexvariables.use")
                && (!SUBCOMMANDS.contains(action) || action.equals("help") || sender.hasPermission("plexvariables." + action));
    }

    private static List<String> matching(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).limit(100).toList();
    }
}
