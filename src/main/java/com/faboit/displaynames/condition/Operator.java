package com.faboit.displaynames.condition;

/**
 * The comparison operators a condition can be written with, using TAB's tokens so a config
 * copied from a TAB setup means the same thing here.
 *
 * <p><b>Declaration order is load-bearing.</b> The parser walks a check left to right and, at
 * each position, tries these in order, so a longer token has to come before any shorter one it
 * starts with - otherwise {@code !=} would parse as a literal {@code !} followed by {@code =},
 * and {@code !<-} as {@code !} followed by {@code <-}. {@link #VALUES} is the array it scans and
 * {@code OperatorTest} pins the ordering.
 */
public enum Operator {

    // Three characters first, then two, then one; within a width the order does not matter.
    NOT_CONTAINS("!<-"),
    NOT_STARTS_WITH("!|-"),
    NOT_ENDS_WITH("!-|"),

    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<="),
    NOT_EQUALS("!="),
    CONTAINS("<-"),
    STARTS_WITH("|-"),
    ENDS_WITH("-|"),

    GREATER(">"),
    LESS("<"),
    EQUALS("=");

    /** Cached because {@code values()} clones the array on every call and this runs per check. */
    static final Operator[] VALUES = values();

    private final String token;

    Operator(String token) {
        this.token = token;
    }

    /** The characters that spell this operator in config.yml. */
    public String token() {
        return token;
    }

    /** {@code true} for the four operators that compare the two sides as numbers. */
    public boolean numeric() {
        return this == GREATER || this == GREATER_OR_EQUAL || this == LESS || this == LESS_OR_EQUAL;
    }

    /**
     * Applies the operator to two already-resolved sides.
     *
     * <p>Text comparisons are case-insensitive. TAB's are not, and this is a deliberate
     * difference: every operand here comes out of a placeholder whose casing the server owner
     * does not control ({@code YES} vs {@code yes}, {@code Survival} vs {@code survival}), and a
     * condition that silently never fires is far harder to diagnose than one that matches a
     * little too readily.
     *
     * <p>A numeric operator applied to something that is not a number is simply {@code false}:
     * an unresolved {@code %placeholder%} would otherwise throw once per player per tick.
     */
    public boolean matches(String left, String right) {
        return switch (this) {
            case EQUALS -> left.equalsIgnoreCase(right);
            case NOT_EQUALS -> !left.equalsIgnoreCase(right);
            case CONTAINS -> containsIgnoreCase(left, right);
            case NOT_CONTAINS -> !containsIgnoreCase(left, right);
            case STARTS_WITH -> left.regionMatches(true, 0, right, 0, right.length());
            case NOT_STARTS_WITH -> !left.regionMatches(true, 0, right, 0, right.length());
            case ENDS_WITH -> endsWithIgnoreCase(left, right);
            case NOT_ENDS_WITH -> !endsWithIgnoreCase(left, right);
            // Every comparison against NaN is false, so a non-numeric side fails all four.
            case GREATER -> number(left) > number(right);
            case GREATER_OR_EQUAL -> number(left) >= number(right);
            case LESS -> number(left) < number(right);
            case LESS_OR_EQUAL -> number(left) <= number(right);
        };
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        int limit = haystack.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    private static boolean endsWithIgnoreCase(String text, String suffix) {
        int start = text.length() - suffix.length();
        return start >= 0 && text.regionMatches(true, start, suffix, 0, suffix.length());
    }

    /**
     * Parses a number without ever throwing.
     *
     * <p>This runs on the refresh path for every numeric check on every player, and the input is
     * whatever a placeholder returned - frequently an error string or an unresolved
     * {@code %token%} when an expansion is missing. Filling in a stack trace for each of those
     * would cost more than the whole nametag. The pre-scan rejects the common cases without
     * touching the exception machinery at all.
     *
     * @return the value, or {@code NaN} for anything that is not a plain number
     */
    static double number(String text) {
        int length = text.length();
        if (length == 0) return Double.NaN;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            boolean plausible = (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+'
                    || c == 'e' || c == 'E';
            if (!plausible) return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            // "1.2.3", "-", "e" and friends survive the pre-scan; NaN loses every comparison.
            return Double.NaN;
        }
    }
}
