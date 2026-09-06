package com.faboit.displaynames.condition;

import com.faboit.displaynames.text.PlaceholderResolver;
import com.faboit.displaynames.text.Placeholders;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Every condition declared in config.yml, plus the parser that reads them.
 *
 * <p>Two things use this. Text expansion replaces {@code %condition:name%} with the condition's
 * {@code true}/{@code false} string, the way TAB does. Gating hands a whole {@link Condition} to
 * {@code Settings}, which is what lets a condition pick a <em>profile</em> - and with it an
 * offset, a scale, a billboard, a render distance - rather than only a piece of text.
 *
 * <p>The registry is immutable once loaded and carries no per-player state, so the same instance
 * is read from every region thread at once. Evaluation depth is threaded through the calls
 * instead of being kept anywhere, for the same reason.
 *
 * <p>When no conditions are declared the whole feature costs nothing: {@link #expand} returns the
 * string it was given, by identity, without scanning it.
 */
public final class Conditions {

    /** How {@code %condition:name%} is spelled. */
    private static final String REFERENCE_PREFIX = "condition:";

    /**
     * How many condition references deep evaluation may go.
     *
     * <p>Cycles are found and dropped at load, so reaching this needs a chain of genuinely
     * distinct conditions. It is a backstop against a shape the load-time check does not model,
     * not the primary defence - and it has to exist, because the alternative on the refresh path
     * is a StackOverflowError on a region thread.
     */
    private static final int MAX_DEPTH = 16;

    private static final Operand TRUE = Operand.of("true");

    /** The registry a server with no {@code conditions:} section gets. */
    public static final Conditions NONE = new Conditions(Map.of());

    /** Keyed by lowercased name, in declaration order so listings are predictable. */
    private final Map<String, Condition> byName;

    private Conditions(Map<String, Condition> byName) {
        this.byName = byName;
    }

    // ---------------------------------------------------------------- loading

    /** Reads the {@code conditions:} section; a missing or unusable section yields {@link #NONE}. */
    public static Conditions load(ConfigurationSection root, Logger logger) {
        if (root == null) return NONE;

        Map<String, Condition> byName = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            Condition condition = read(id, root, logger);
            if (condition == null) continue;
            if (byName.putIfAbsent(id.toLowerCase(Locale.ROOT), condition) != null) {
                warn(logger, "Condition '" + id + "' is declared twice (names are case-insensitive); "
                        + "the later one was ignored.");
            }
        }
        if (byName.isEmpty()) return NONE;
        dropCycles(byName, logger);
        return byName.isEmpty() ? NONE : new Conditions(byName);
    }

    /** Reads one entry, which may be the full block form or a bare expression. */
    private static Condition read(String id, ConfigurationSection root, Logger logger) {
        ConfigurationSection entry = root.getConfigurationSection(id);
        if (entry == null) {
            List<String> listed = root.getStringList(id);
            if (!listed.isEmpty()) return build(id, listed, true, "true", "false", logger);
            return parseExpression(id, root.getString(id), logger);
        }

        // YAML `true:` and `false:` are boolean keys; Bukkit stores keys by toString(), so they
        // are read back under those names. Quoted "true:" works out the same either way.
        String pass = entry.getString("true", "true");
        String fail = entry.getString("false", "false");

        List<String> listed = entry.getStringList("conditions");
        if (!listed.isEmpty()) {
            boolean requireAll = !"OR".equalsIgnoreCase(entry.getString("type", "AND"));
            return build(id, listed, requireAll, pass, fail, logger);
        }

        String inline = entry.getString("conditions");
        if (inline == null || inline.isBlank()) {
            warn(logger, "Condition '" + id + "' has no `conditions:` list and was dropped.");
            return null;
        }
        Expression expression = Expression.of(inline);
        return build(id, expression.parts(), expression.requireAll(), pass, fail, logger);
    }

    /** Parses a condition written as a single string, splitting {@code ;} (AND) or {@code |} (OR). */
    private static Condition parseExpression(String id, String source, Logger logger) {
        if (source == null || source.isBlank()) {
            warn(logger, "Condition '" + id + "' is empty and was dropped.");
            return null;
        }
        Expression expression = Expression.of(source);
        return build(id, expression.parts(), expression.requireAll(), "true", "false", logger);
    }

    private static Condition build(String id, List<String> parts, boolean requireAll,
                                   String pass, String fail, Logger logger) {
        List<Check> checks = new ArrayList<>(parts.size());
        for (String part : parts) {
            Check check = parseCheck(part);
            if (check == null) {
                warn(logger, "Condition '" + id + "': '" + part.trim() + "' is not a check I can read. "
                        + "Expected something like '%placeholder%=value', '%placeholder%>=10' or "
                        + "'permission:some.node'.");
                continue;
            }
            checks.add(check);
        }
        if (checks.isEmpty()) {
            warn(logger, "Condition '" + id + "' has no usable checks and was dropped.");
            return null;
        }
        return new Condition(id, checks.toArray(new Check[0]), requireAll, pass, fail,
                referencesIn(parts, pass, fail));
    }

    /**
     * Turns one check into its parsed form.
     *
     * <p>Order matters: {@code permission:} is recognised before any operator search, and the
     * search itself is leftmost-longest so {@code !=} never reads as {@code =}.
     *
     * @return the check, or {@code null} when the text is not a check at all
     */
    static Check parseCheck(String source) {
        String text = source.trim();
        if (text.isEmpty()) return null;

        boolean negated = text.charAt(0) == '!';
        String body = negated ? text.substring(1).trim() : text;

        if (body.regionMatches(true, 0, "permission:", 0, "permission:".length())) {
            String node = body.substring("permission:".length()).trim();
            return node.isEmpty() ? null : new Check.Permission(node, negated);
        }

        int index = operatorIndex(text);
        if (index >= 0) {
            Operator operator = operatorAt(text, index);
            return new Check.Comparison(Operand.of(text.substring(0, index)), operator,
                    Operand.of(text.substring(index + operator.token().length())));
        }

        // No operator: a bare %placeholder% asks whether it says "true", and !%placeholder%
        // whether it does not. Anything else is a plain word with nothing to compare it to.
        Operand only = Operand.of(body);
        if (!only.dynamic()) return null;
        return new Check.Comparison(only, negated ? Operator.NOT_EQUALS : Operator.EQUALS, TRUE);
    }

    /**
     * Finds the operator in a check, ignoring anything inside {@code %...%}.
     *
     * <p>Placeholder names are full of operator characters - {@code %luckperms_meta_rank-name%}
     * ends in {@code -}, and an expansion argument can hold almost anything - so the scan skips
     * whole markers rather than trying to be clever about which side of one it is on.
     *
     * @return the index the operator starts at, or {@code -1}
     */
    private static int operatorIndex(String text) {
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (text.charAt(i) == '%') {
                int close = text.indexOf('%', i + 1);
                if (close > i + 1) {
                    i = close + 1;
                    continue;
                }
                i++;
                continue;
            }
            if (operatorAt(text, i) != null) return i;
            i++;
        }
        return -1;
    }

    /** The longest operator starting exactly at {@code index}, relying on Operator's ordering. */
    private static Operator operatorAt(String text, int index) {
        for (Operator operator : Operator.VALUES) {
            if (text.startsWith(operator.token(), index)) return operator;
        }
        return null;
    }

    /** Names of the conditions this one refers to, so the loader can spot a cycle. */
    private static Set<String> referencesIn(List<String> parts, String pass, String fail) {
        Set<String> names = new HashSet<>();
        // A lookup that always returns null substitutes nothing, so this is a scan, not a rewrite.
        Function<String, String> collector = token -> {
            String name = referenceName(token);
            if (name != null) names.add(name);
            return null;
        };
        for (String part : parts) {
            Placeholders.replace(part, collector);
        }
        Placeholders.replace(pass, collector);
        Placeholders.replace(fail, collector);
        return names.isEmpty() ? Set.of() : names;
    }

    /**
     * Removes conditions that could evaluate themselves.
     *
     * <p>Settles the conditions that reference nothing, then everything reachable from those, and
     * drops whatever is left - which is exactly the set that sits on a cycle. A reference to a
     * name nobody declared is not a cycle: it stays as literal text at runtime.
     */
    private static void dropCycles(Map<String, Condition> byName, Logger logger) {
        Set<String> settled = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Condition> entry : byName.entrySet()) {
                if (settled.contains(entry.getKey())) continue;
                boolean ready = true;
                for (String reference : entry.getValue().references()) {
                    if (byName.containsKey(reference) && !settled.contains(reference)) {
                        ready = false;
                        break;
                    }
                }
                if (!ready) continue;
                settled.add(entry.getKey());
                progress = true;
            }
        }
        if (settled.size() == byName.size()) return;

        Iterator<Map.Entry<String, Condition>> remaining = byName.entrySet().iterator();
        while (remaining.hasNext()) {
            Map.Entry<String, Condition> entry = remaining.next();
            if (settled.contains(entry.getKey())) continue;
            warn(logger, "Condition '" + entry.getValue().id() + "' refers back to itself, directly "
                    + "or through another condition, and was dropped.");
            remaining.remove();
        }
    }

    /**
     * Resolves a config value that names a condition, or spells one out inline.
     *
     * <p>Used by {@code profiles.<id>.condition} and {@code visibility.hide-condition}, so both
     * accept {@code my_condition}, {@code %condition:my_condition%} and {@code "%afk%=true"}.
     *
     * @param where config path quoted back in the warning, so a typo is findable
     * @return the condition, or {@code null} when the value is absent or unusable
     */
    public Condition reference(String value, String where, Logger logger) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;

        // A whole value of %condition:name% means the name; anything else keeps its markers.
        if (text.length() > 2 && text.charAt(0) == '%' && text.charAt(text.length() - 1) == '%'
                && text.indexOf('%', 1) == text.length() - 1) {
            String name = referenceName(text.substring(1, text.length() - 1));
            if (name != null) text = name;
        }

        Condition named = byName.get(text.toLowerCase(Locale.ROOT));
        if (named != null) return named;

        Condition inline = parseExpression(text, text, null);
        if (inline != null) return inline;

        warn(logger, where + ": '" + value + "' is neither a condition declared under `conditions:` "
                + "nor an expression I can read, so it was ignored.");
        return null;
    }

    // ---------------------------------------------------------------- evaluation

    /**
     * Whether a player satisfies a condition.
     *
     * <p>A {@code null} condition means "no condition was configured", which passes: the caller
     * asked whether anything blocks this, and nothing does.
     */
    public boolean matches(Player player, Condition condition, PlaceholderResolver base) {
        return condition == null || condition.matches(player, this, base, 0);
    }

    /** Replaces {@code %condition:name%} references; other placeholders are left alone. */
    public String expand(Player player, String text, PlaceholderResolver base) {
        return expandAt(player, text, base, 0);
    }

    /** Expands condition references, then hands the result to the placeholder resolver. */
    public String resolve(Player player, String text, PlaceholderResolver base) {
        return resolveAt(player, text, base, 0);
    }

    String expandAt(Player player, String text, PlaceholderResolver base, int depth) {
        // Both exits are the identity: no conditions configured, or too deep to keep going.
        if (text == null || byName.isEmpty() || depth >= MAX_DEPTH) return text;
        int next = depth + 1;
        return Placeholders.replace(text, token -> {
            String name = referenceName(token);
            if (name == null) return null;
            Condition condition = byName.get(name);
            return condition == null ? null : condition.evaluate(player, this, base, next);
        });
    }

    String resolveAt(Player player, String text, PlaceholderResolver base, int depth) {
        return base.resolve(player, expandAt(player, text, base, depth));
    }

    /** The condition name inside a {@code condition:name} token, or {@code null}. */
    private static String referenceName(String token) {
        if (token == null
                || !token.regionMatches(true, 0, REFERENCE_PREFIX, 0, REFERENCE_PREFIX.length())) {
            return null;
        }
        String name = token.substring(REFERENCE_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
    }

    // ---------------------------------------------------------------- accessors

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public int size() {
        return byName.size();
    }

    /** Every declared condition, in the order config.yml lists them. */
    public Collection<Condition> all() {
        return byName.values();
    }

    /** Looks a condition up by name, case-insensitively. */
    public Condition named(String name) {
        return name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) logger.warning(message);
    }

    /** A condition expression split into its parts, with the combinator the separator implied. */
    private record Expression(List<String> parts, boolean requireAll) {

        /** {@code ;} means AND and wins over {@code |}, which means OR; neither means one check. */
        static Expression of(String source) {
            List<String> and = splitTop(source, ';');
            if (and.size() > 1) return new Expression(and, true);
            List<String> or = splitTop(source, '|');
            if (or.size() > 1) return new Expression(or, false);
            return new Expression(List.of(source), true);
        }

        /** Splits on a separator that is not inside {@code %...%} and not part of an operator. */
        private static List<String> splitTop(String text, char separator) {
            List<String> parts = new ArrayList<>(2);
            int start = 0;
            int i = 0;
            int length = text.length();
            while (i < length) {
                char c = text.charAt(i);
                if (c == '%') {
                    int close = text.indexOf('%', i + 1);
                    if (close > i + 1) {
                        i = close + 1;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (c == separator && !partOfOperator(text, i, separator)) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                }
                i++;
            }
            parts.add(text.substring(start));
            parts.removeIf(String::isBlank);
            return parts;
        }

        /**
         * Keeps the OR separator from eating an operator.
         *
         * <p>{@code |} is both "or" and half of {@code |-} (starts with) and {@code -|} (ends
         * with), so a bar next to a dash belongs to the operator.
         */
        private static boolean partOfOperator(String text, int index, char separator) {
            if (separator != '|') return false;
            if (index + 1 < text.length() && text.charAt(index + 1) == '-') return true;
            return index > 0 && text.charAt(index - 1) == '-';
        }
    }
}
