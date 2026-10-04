package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.condition.CompiledCondition;
import dev.plex.plexvariables.condition.ConditionParser;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.expression.CompiledExpression;
import dev.plex.plexvariables.expression.ExpressionException;
import dev.plex.plexvariables.expression.ExpressionFormatting;
import dev.plex.plexvariables.expression.ExpressionParser;
import dev.plex.plexvariables.storage.StoredVariableScope;

import java.io.IOException;
import java.io.Reader;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

public final class VariableManager {
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final Logger logger;

    public VariableManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LoadResult load(Path folder) throws IOException {
        return load(folder, PluginSettings.defaults());
    }

    public LoadResult load(Path folder, PluginSettings settings) throws IOException {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(settings, "settings");
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Variable directory does not exist or is not a directory: " + folder);
        }

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort((left, right) -> source(folder, left).compareTo(source(folder, right)));

        Map<String, VariableDefinition> definitions = new LinkedHashMap<>();
        int loaded = 0;
        int skipped = 0;
        for (Path file : files) {
            String source = source(folder, file);
            Object document;
            try {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    document = new Yaml(new StringKeyConstructor(options)).load(reader);
                }
            } catch (IOException | YAMLException exception) {
                logger.warning("Skipping variable file " + source + ": " + exception.getMessage());
                skipped++;
                continue;
            }
            if (!(document instanceof Map<?, ?> root) || !(root.get("variables") instanceof Map<?, ?> section)) {
                logger.warning("Skipping variable file " + source + ": missing variables section");
                skipped++;
                continue;
            }
            loaded++;
            for (Map.Entry<?, ?> item : section.entrySet()) {
                if (!(item.getKey() instanceof String rawId) || !VALID_ID.matcher(rawId).matches()) {
                    logger.warning("Skipping invalid variable ID '" + item.getKey() + "' in " + source);
                    continue;
                }
                Object entry = item.getValue();
                VariableDefinition definition = null;
                String id = rawId.toLowerCase(Locale.ROOT);

                if (entry instanceof Map<?, ?> variable) {
                    boolean hasValue = variable.containsKey("value");
                    boolean hasConditions = variable.containsKey("conditions");
                    boolean hasExpression = variable.containsKey("expression");

                    int count = (hasValue ? 1 : 0) + (hasConditions ? 1 : 0) + (hasExpression ? 1 : 0);
                    if (count > 1) {
                        List<String> conflicts = new ArrayList<>();
                        if (hasValue) conflicts.add("'value'");
                        if (hasConditions) conflicts.add("'conditions'");
                        if (hasExpression) conflicts.add("'expression'");
                        logger.warning("Skipping variable '" + rawId + "' in " + source + ": conflicting fields " + String.join(", ", conflicts));
                        continue;
                    }

                    Object rawType = variable.get("type");
                    String typeText = rawType instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
                    if (rawType != null && typeText == null) {
                        logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'type' must be text");
                        continue;
                    }

                    if (typeText != null && !typeText.equals("static") && !typeText.equals("conditional")
                            && !typeText.equals("expression") && !typeText.equals("stored")) {
                        logger.warning("Skipping variable '" + rawId + "' in " + source + ": unsupported type " + rawType);
                        continue;
                    }

                    boolean isStored = "stored".equals(typeText);
                    boolean isExpression = "expression".equals(typeText) || (typeText == null && hasExpression);
                    boolean isConditional = "conditional".equals(typeText) || (typeText == null && hasConditions);

                    if (isStored) {
                        if (hasValue || hasConditions || hasExpression) {
                            logger.warning("Skipping stored variable '" + rawId + "' in " + source + ": cannot have value, conditions, or expression");
                            continue;
                        }

                        Object scopeObj = variable.get("scope");
                        if (scopeObj == null || (!(scopeObj instanceof String scopeStr))) {
                            logger.warning("Skipping stored variable '" + rawId + "' in " + source + ": missing or invalid 'scope'");
                            continue;
                        }

                        StoredVariableScope scope;
                        try {
                            scope = StoredVariableScope.valueOf(scopeStr.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException e) {
                            logger.warning("Skipping stored variable '" + rawId + "' in " + source + ": invalid scope '" + scopeStr + "', expected player or global");
                            continue;
                        }

                        Object defaultObj = variable.get("default");
                        String defaultValue = "";
                        if (defaultObj != null) {
                            if (!(defaultObj instanceof String) && !(defaultObj instanceof Number) && !(defaultObj instanceof Boolean)) {
                                logger.warning("Skipping stored variable '" + rawId + "' in " + source + ": 'default' must be a scalar value");
                                continue;
                            }
                            defaultValue = String.valueOf(defaultObj);
                        }

                        definition = VariableDefinition.ofStored(id, scope, defaultValue, source);
                    } else if (isExpression) {
                        Object exprObj = variable.get("expression");
                        if (exprObj == null || (!(exprObj instanceof String) && !(exprObj instanceof Number))) {
                            logger.warning("Skipping variable '" + rawId + "' in " + source + ": missing or non-scalar 'expression'");
                            continue;
                        }
                        String rawExpression = String.valueOf(exprObj);
                        CompiledExpression compiled;
                        try {
                            compiled = ExpressionParser.parse(rawExpression, settings.maxExpressionLength(), settings.maxExpressionTokens(), settings.maxExpressionParenthesisDepth());
                        } catch (ExpressionException e) {
                            logger.warning("Skipping invalid expression variable '" + rawId + "' in " + source + ": " + e.getMessage());
                            continue;
                        }

                        int decimals = -1;
                        Object decObj = variable.get("decimals");
                        if (decObj != null) {
                            if (decObj instanceof Number num && num.doubleValue() == num.intValue() && num.intValue() >= 0) {
                                decimals = num.intValue();
                            } else {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'decimals' must be a non-negative integer");
                                continue;
                            }
                        }

                        boolean stripTrailingZeros = true;
                        Object stripObj = variable.get("strip-trailing-zeros");
                        if (stripObj instanceof Boolean flag) {
                            stripTrailingZeros = flag;
                        } else if (stripObj != null) {
                            logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'strip-trailing-zeros' must be a boolean");
                            continue;
                        }

                        RoundingMode roundingMode = RoundingMode.HALF_UP;
                        Object rmObj = variable.get("rounding-mode");
                        if (rmObj != null) {
                            if (rmObj instanceof String rmStr) {
                                try {
                                    roundingMode = RoundingMode.valueOf(rmStr.toUpperCase(Locale.ROOT));
                                } catch (IllegalArgumentException e) {
                                    logger.warning("Skipping variable '" + rawId + "' in " + source + ": invalid rounding mode '" + rmStr + "'");
                                    continue;
                                }
                            } else {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'rounding-mode' must be text");
                                continue;
                            }
                        }

                        String prefix = null;
                        Object preObj = variable.get("prefix");
                        if (preObj != null) {
                            if (preObj instanceof String || preObj instanceof Number || preObj instanceof Boolean) {
                                prefix = String.valueOf(preObj);
                            } else {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'prefix' must be a scalar");
                                continue;
                            }
                        }

                        String suffix = null;
                        Object sufObj = variable.get("suffix");
                        if (sufObj != null) {
                            if (sufObj instanceof String || sufObj instanceof Number || sufObj instanceof Boolean) {
                                suffix = String.valueOf(sufObj);
                            } else {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'suffix' must be a scalar");
                                continue;
                            }
                        }

                        boolean thousandsSeparator = false;
                        Object tsObj = null;
                        if (variable.get("format") instanceof Map<?, ?> formatMap) {
                            tsObj = formatMap.get("thousands-separator");
                        }
                        if (tsObj == null) {
                            tsObj = variable.get("thousands-separator");
                        }
                        if (tsObj instanceof Boolean flag) {
                            thousandsSeparator = flag;
                        } else if (tsObj != null) {
                            logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'thousands-separator' must be a boolean");
                            continue;
                        }

                        String onError = null;
                        Object errObj = variable.get("on-error");
                        if (errObj != null) {
                            if (errObj instanceof String || errObj instanceof Number || errObj instanceof Boolean) {
                                onError = String.valueOf(errObj);
                            } else {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'on-error' must be a scalar");
                                continue;
                            }
                        }

                        ExpressionFormatting formatting = new ExpressionFormatting(decimals, stripTrailingZeros, roundingMode, prefix, suffix, thousandsSeparator);
                        definition = VariableDefinition.ofExpression(id, compiled, formatting, onError, source);
                    } else if (isConditional) {
                        Object conditionsObj = variable.get("conditions");
                        if (!(conditionsObj instanceof List<?> rawList) || rawList.isEmpty()) {
                            logger.warning("Skipping variable '" + rawId + "' in " + source + ": missing or empty 'conditions' list");
                            continue;
                        }

                        List<CompiledCondition> compiledList = new ArrayList<>();
                        boolean valid = true;
                        int condIndex = 0;
                        for (Object condItem : rawList) {
                            condIndex++;
                            if (!(condItem instanceof Map<?, ?> condMap)) {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": condition #" + condIndex + " must be a map section");
                                valid = false;
                                break;
                            }

                            Object condRaw = condMap.get("condition");
                            if (condRaw == null) condRaw = condMap.get("if");
                            Object valRaw = condMap.get("value");
                            if (valRaw == null) valRaw = condMap.get("then");

                            if (condRaw == null || valRaw == null) {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": condition #" + condIndex + " missing 'condition' or 'value'");
                                valid = false;
                                break;
                            }

                            if (!(condRaw instanceof String || condRaw instanceof Number || condRaw instanceof Boolean)
                                    || !(valRaw instanceof String || valRaw instanceof Number || valRaw instanceof Boolean)) {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": condition #" + condIndex + " condition and value must be scalars");
                                valid = false;
                                break;
                            }

                            try {
                                CompiledCondition compiled = ConditionParser.parse(String.valueOf(condRaw), String.valueOf(valRaw));
                                compiledList.add(compiled);
                            } catch (IllegalArgumentException exception) {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": condition #" + condIndex + " error: " + exception.getMessage());
                                valid = false;
                                break;
                            }
                        }

                        if (!valid) {
                            continue;
                        }

                        Object defaultObj = variable.get("default");
                        String defaultValue = "";
                        if (defaultObj != null) {
                            if (!(defaultObj instanceof String) && !(defaultObj instanceof Number) && !(defaultObj instanceof Boolean)) {
                                logger.warning("Skipping variable '" + rawId + "' in " + source + ": 'default' must be a scalar value");
                                continue;
                            }
                            defaultValue = String.valueOf(defaultObj);
                        }

                        definition = VariableDefinition.ofConditional(id, compiledList, defaultValue, source);
                    } else {
                        Object val = variable.get("value");
                        if (!(val instanceof String) && !(val instanceof Number) && !(val instanceof Boolean)) {
                            logger.warning("Skipping variable '" + rawId + "' in " + source + ": value must be a string, number, or boolean");
                            continue;
                        }
                        definition = VariableDefinition.ofStatic(id, String.valueOf(val), source);
                    }
                } else if (entry instanceof String || entry instanceof Number || entry instanceof Boolean) {
                    definition = VariableDefinition.ofStatic(id, String.valueOf(entry), source);
                } else {
                    logger.warning("Skipping variable '" + rawId + "' in " + source + ": definition must be a scalar or mapping");
                    continue;
                }

                VariableDefinition previous = definitions.put(id, definition);
                if (previous != null) {
                    logger.warning("Variable '" + id + "' in " + source + " overrides definition from " + previous.sourceFile());
                }
            }
        }
        return new LoadResult(definitions, loaded, skipped);
    }

    private static String source(Path folder, Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }

    private static final class StringKeyConstructor extends SafeConstructor {
        private StringKeyConstructor(LoaderOptions options) {
            super(options);
        }

        @Override
        protected void flattenMapping(MappingNode node, boolean forceStringKeys) {
            for (var entry : node.getValue()) {
                if (entry.getKeyNode() instanceof ScalarNode key && !Tag.MERGE.equals(key.getTag())) {
                    key.setTag(Tag.STR);
                }
            }
            super.flattenMapping(node, forceStringKeys);
        }
    }

    public record LoadResult(Map<String, VariableDefinition> variables, int filesLoaded, int skippedFiles) {
        public LoadResult {
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }
}
