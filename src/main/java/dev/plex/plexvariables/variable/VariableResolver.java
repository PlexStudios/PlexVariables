package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.condition.CompiledCondition;
import dev.plex.plexvariables.condition.ConditionEvaluator;
import dev.plex.plexvariables.condition.EvaluationTrace;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.expression.CompiledExpression;
import dev.plex.plexvariables.expression.ExpressionEvaluator;
import dev.plex.plexvariables.expression.ExpressionException;
import dev.plex.plexvariables.expression.ExpressionFormatting;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.storage.StoredVariableScope;
import dev.plex.plexvariables.util.ColorUtil;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class VariableResolver {
    private static final String OWN_PREFIX = "plexvar_";

    private final Supplier<PluginState> state;
    private final BiFunction<OfflinePlayer, String, String> parser;
    private final StorageManager storageManager;
    private final ResolutionWarnings warnings;
    private final ThreadLocal<Request> currentRequest = new ThreadLocal<>();

    public VariableResolver(Supplier<PluginState> state,
                             BiFunction<OfflinePlayer, String, String> parser, Logger logger) {
        this(state, parser, null, logger);
    }

    public VariableResolver(Supplier<PluginState> state,
                             BiFunction<OfflinePlayer, String, String> parser,
                             StorageManager storageManager,
                             Logger logger) {
        this.state = Objects.requireNonNull(state, "state");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.storageManager = storageManager;
        this.warnings = new ResolutionWarnings(logger);
    }

    public String resolve(OfflinePlayer player, String id) {
        if (id == null) return null;
        Request request = currentRequest.get();
        boolean root = request == null;
        if (root) {
            PluginState snapshot = Objects.requireNonNull(state.get(), "state snapshot");
            VariableDefinition definition = findDefinition(snapshot.variables(), id.toLowerCase(Locale.ROOT));
            if (definition == null) return null;
            if (definition.type() == VariableType.STATIC && definition.value() != null && definition.value().indexOf('%') < 0) {
                return finish(snapshot.settings(), definition, definition.value());
            }
            request = new Request(snapshot);
            currentRequest.set(request);
        }
        try {
            String result = resolveVariable(request, player, id);
            VariableDefinition definition = findDefinition(request.variables, id.toLowerCase(Locale.ROOT));
            return root ? finish(request.settings, definition, result) : result;
        } finally {
            if (root) currentRequest.remove();
        }
    }

    public String parse(OfflinePlayer player, String text) {
        if (text == null) return null;
        Request request = currentRequest.get();
        boolean root = request == null;
        if (root) {
            PluginState snapshot = Objects.requireNonNull(state.get(), "state snapshot");
            if (text.indexOf('%') < 0) return finish(snapshot.settings(), null, text);
            request = new Request(snapshot);
            currentRequest.set(request);
        }
        try {
            String result = expand(request, player, text);
            if (result == null) {
                warn(request, null, "output limit");
                result = request.settings.errorValue();
            }
            return root ? finish(request.settings, null, result) : result;
        } finally {
            if (root) currentRequest.remove();
        }
    }

    public EvaluationTrace test(OfflinePlayer player, String id) {
        if (id == null) return null;
        PluginState snapshot = Objects.requireNonNull(state.get(), "state snapshot");
        VariableDefinition definition = findDefinition(snapshot.variables(), id.toLowerCase(Locale.ROOT));
        if (definition == null) return null;

        EvaluationTrace trace = new EvaluationTrace(definition.id(), definition.type().name(), definition.sourceFile());
        long started = System.nanoTime();

        Request request = new Request(snapshot);
        currentRequest.set(request);
        try {
            request.stack.push(definition);
            request.active.add(definition.id());
            String result = resolveDefinition(request, player, definition, trace);
            String finalOutput = finish(request.settings, definition, result);
            trace.setFinalResult(finalOutput);
            trace.setElapsedMs((System.nanoTime() - started) / 1_000_000.0);
            return trace;
        } finally {
            currentRequest.remove();
        }
    }

    public void clearWarnings() {
        warnings.clear();
    }

    private VariableDefinition findDefinition(Map<String, VariableDefinition> variables, String normalizedId) {
        VariableDefinition exact = variables.get(normalizedId);
        if (exact != null) return exact;
        if (normalizedId.startsWith("global_")) {
            String globalId = normalizedId.substring(7);
            VariableDefinition globalDef = variables.get(globalId);
            if (globalDef != null && globalDef.type() == VariableType.STORED && globalDef.scope() == StoredVariableScope.GLOBAL) {
                return globalDef;
            }
        }
        return null;
    }

    private String resolveVariable(Request request, OfflinePlayer player, String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        VariableDefinition definition = findDefinition(request.variables, normalized);
        if (definition == null) return null;
        String activeId = definition.id();
        if (request.active.contains(activeId)) {
            warn(request, definition, "cycle: " + resolutionPath(request, activeId));
            return request.settings.errorValue();
        }
        if (request.stack.size() >= request.settings.maxResolutionDepth()) {
            warn(request, definition, "depth limit (" + request.settings.maxResolutionDepth()
                    + "): " + resolutionPath(request, activeId));
            return request.settings.errorValue();
        }
        if (!request.takeWork()) {
            warn(request, definition, "work limit");
            return request.settings.errorValue();
        }
        request.stack.push(definition);
        request.active.add(activeId);
        try {
            String result = resolveDefinition(request, player, definition, null);
            if (result != null) return result;
            warn(request, definition, "output limit");
            return request.settings.errorValue();
        } finally {
            request.active.remove(activeId);
            request.stack.pop();
        }
    }

    private String resolveDefinition(Request request, OfflinePlayer player, VariableDefinition definition, EvaluationTrace trace) {
        if (definition.type() == VariableType.STATIC) {
            if (trace != null) {
                trace.setSelectedBranch("static");
            }
            return expand(request, player, definition.value());
        }

        if (definition.type() == VariableType.STORED) {
            String storedValue = null;
            String cacheState = "LOADED";

            if (definition.scope() == StoredVariableScope.GLOBAL) {
                storedValue = storageManager != null ? storageManager.getGlobalValue(definition.id()) : null;
            } else if (definition.scope() == StoredVariableScope.PLAYER) {
                if (player != null && storageManager != null) {
                    cacheState = storageManager.getPlayerCacheState(player.getUniqueId()).name();
                    storedValue = storageManager.getPlayerValue(player.getUniqueId(), definition.id());
                } else {
                    cacheState = "UNLOADED";
                }
            }

            String effectiveRaw = storedValue != null ? storedValue : (definition.defaultValue() != null ? definition.defaultValue() : "");

            if (trace != null) {
                trace.setStoredDetails(
                        definition.scope() != null ? definition.scope().name() : "PLAYER",
                        cacheState,
                        storedValue != null ? storedValue : "<none>",
                        definition.defaultValue() != null ? definition.defaultValue() : "",
                        effectiveRaw
                );
            }

            return expand(request, player, effectiveRaw);
        }

        if (definition.type() == VariableType.EXPRESSION) {
            if (!request.takeWork()) {
                warn(request, definition, "work limit");
                return request.settings.errorValue();
            }

            CompiledExpression compiled = definition.compiledExpression();
            ExpressionFormatting fmt = definition.expressionFormatting();
            StringBuilder resolvedExprBuf = trace != null ? new StringBuilder(compiled.rawExpression()) : null;

            Function<String, String> placeholderResolver = rawToken -> {
                String res = expand(request, player, rawToken);
                if (trace != null && resolvedExprBuf != null) {
                    int idx = resolvedExprBuf.indexOf(rawToken);
                    if (idx >= 0) {
                        resolvedExprBuf.replace(idx, idx + rawToken.length(), res != null ? res : "null");
                    }
                }
                return res;
            };

            try {
                BigDecimal numberResult = ExpressionEvaluator.evaluate(compiled, placeholderResolver);
                String formattedNum = fmt.formatNumber(numberResult);

                String prefixStr = fmt.prefix() != null ? expand(request, player, fmt.prefix()) : "";
                if (prefixStr == null) return null;
                String suffixStr = fmt.suffix() != null ? expand(request, player, fmt.suffix()) : "";
                if (suffixStr == null) return null;

                String finalResult = prefixStr + formattedNum + suffixStr;

                if (trace != null) {
                    String formatSummary = "Decimals: " + (fmt.decimals() >= 0 ? fmt.decimals() : "auto")
                            + " | Trailing Zeros: " + (fmt.stripTrailingZeros() ? "stripped" : "kept")
                            + " | Thousands Separator: " + fmt.thousandsSeparator();
                    trace.setExpressionDetails(compiled.rawExpression(), resolvedExprBuf.toString(), formattedNum, formatSummary);
                }

                return finalResult;
            } catch (ExpressionException exception) {
                warn(request, definition, "expression error (" + exception.getMessage() + ")");
                String fallback = definition.onError() != null
                        ? expand(request, player, definition.onError())
                        : request.settings.errorValue();

                if (trace != null) {
                    trace.setExpressionError(compiled.rawExpression(),
                            resolvedExprBuf != null ? resolvedExprBuf.toString() : compiled.rawExpression(),
                            exception.getMessage());
                }

                return fallback;
            }
        }

        boolean caseSensitive = request.settings.caseSensitiveConditions();
        BiFunction<OfflinePlayer, String, String> expressionResolver = (p, text) -> expand(request, p, text);

        List<CompiledCondition> conditions = definition.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            CompiledCondition cond = conditions.get(i);
            if (!request.takeWork()) {
                warn(request, definition, "work limit");
                return request.settings.errorValue();
            }
            boolean matched = ConditionEvaluator.evaluate(cond, player, expressionResolver, caseSensitive, trace, i + 1);
            if (matched) {
                if (trace != null) {
                    trace.setSelectedBranch("Condition #" + (i + 1));
                }
                return expand(request, player, cond.value());
            }
        }

        if (trace != null) {
            trace.setSelectedBranch("default");
        }
        String defaultValue = definition.defaultValue() != null ? definition.defaultValue() : "";
        return expand(request, player, defaultValue);
    }

    private String expand(Request request, OfflinePlayer player, String text) {
        if (text == null) return null;
        int maxLength = request.settings.maxOutputLength();
        if (text.indexOf('%') < 0) return text.length() <= maxLength ? text : null;

        StringBuilder output = new StringBuilder(Math.min(text.length(), maxLength));
        for (int index = 0; index < text.length();) {
            char current = text.charAt(index);
            if (current != '%') {
                if (output.length() == maxLength) return null;
                output.append(current);
                index++;
                continue;
            }
            int close = text.indexOf('%', index + 1);
            if (close < 0 || !isTokenBody(text, index + 1, close)) {
                if (output.length() == maxLength) return null;
                output.append('%');
                index++;
                continue;
            }
            String token = text.substring(index, close + 1);
            String body = text.substring(index + 1, close);
            String replacement;
            if (body.regionMatches(true, 0, OWN_PREFIX, 0, OWN_PREFIX.length())) {
                replacement = resolveVariable(request, player, body.substring(OWN_PREFIX.length()));
                if (replacement == null) replacement = token;
            } else if (!request.takeWork()) {
                warn(request, null, "work limit");
                replacement = request.settings.errorValue();
            } else {
                try {
                    replacement = parser.apply(player, token);
                    if (replacement == null) replacement = token;
                } catch (Exception exception) {
                    warn(request, null, "parser exception (" + exception.getClass().getSimpleName() + ")");
                    replacement = request.settings.errorValue();
                }
            }
            if (replacement.length() > maxLength - output.length()) return null;
            output.append(replacement);
            index = close + 1;
        }
        return output.toString();
    }

    private static boolean isTokenBody(String text, int start, int end) {
        if (start == end) return false;
        char first = text.charAt(start);
        if (!Character.isLetterOrDigit(first) && first != '_') return false;
        for (int index = start; index < end; index++) {
            if (text.charAt(index) == '_') return true;
            if (Character.isWhitespace(text.charAt(index))) return false;
        }
        return true;
    }

    private static String resolutionPath(Request request, String target) {
        StringBuilder path = new StringBuilder();
        var it = request.stack.descendingIterator();
        while (it.hasNext()) {
            path.append(it.next().id()).append(" \u2192 ");
        }
        path.append(target);
        return path.toString();
    }

    private void warn(Request request, VariableDefinition definition, String cause) {
        VariableDefinition origin = definition != null ? definition : request.stack.peek();
        warnings.warn(origin == null ? "<text>" : origin.id(),
                origin == null ? "<input>" : origin.sourceFile(), cause,
                request.settings.warningCooldownSeconds());
    }

    private String finish(PluginSettings settings, VariableDefinition definition, String value) {
        if (value == null) return null;
        if (value.length() > settings.maxOutputLength()) {
            warnRoot(settings, definition, "output limit");
            return settings.errorValue();
        }
        if (!settings.colorizePlaceholderOutput() || (value.indexOf('&') < 0 && value.indexOf('§') < 0)) {
            return value;
        }
        String colored = ColorUtil.legacy(value);
        if (colored.length() > settings.maxOutputLength()) {
            warnRoot(settings, definition, "output limit after color conversion");
            return settings.errorValue();
        }
        return colored;
    }

    private void warnRoot(PluginSettings settings, VariableDefinition definition, String cause) {
        warnings.warn(definition == null ? "<text>" : definition.id(),
                definition == null ? "<input>" : definition.sourceFile(), cause,
                settings.warningCooldownSeconds());
    }

    private static final class Request {
        private final PluginSettings settings;
        private final Map<String, VariableDefinition> variables;
        private final Deque<VariableDefinition> stack = new ArrayDeque<>();
        private final Set<String> active = new HashSet<>();
        private int work;

        private Request(PluginState snapshot) {
            this.settings = snapshot.settings();
            this.variables = snapshot.variables();
        }

        private boolean takeWork() {
            if (work >= settings.maxExpansions()) return false;
            work++;
            return true;
        }
    }
}
