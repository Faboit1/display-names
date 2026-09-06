package com.faboit.displaynames.text;

import java.util.function.Function;

/**
 * The {@code %token%} scanner shared by everything that has to walk placeholder markers.
 *
 * <p>One implementation because the index arithmetic around {@code %a%%b%} and unclosed markers
 * is easy to get subtly wrong, and because both callers depend on the same guarantee: when
 * nothing was substituted the <em>same</em> string instance comes back, so a scan that finds
 * nothing costs no allocation at all. That is what makes it usable as a plain tokenizer - pass a
 * lookup that records what it sees and always returns {@code null}.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Substitutes every {@code %token%} the lookup recognises, leaving the rest untouched.
     *
     * @param lookup returns the replacement, or {@code null} for a token it does not handle
     * @return the rewritten text, or {@code text} itself when nothing matched
     */
    public static String replace(String text, Function<String, String> lookup) {
        if (text == null || text.indexOf('%') < 0) return text;

        StringBuilder out = null;
        int copied = 0;
        int scan = 0;

        while (true) {
            int open = text.indexOf('%', scan);
            if (open < 0) break;
            int close = text.indexOf('%', open + 1);
            if (close < 0) break;

            String value = lookup.apply(text.substring(open + 1, close));
            if (value == null) {
                // Not one of ours. Resume at the closing marker, which may itself open the
                // next placeholder in a run like %a%%b%.
                scan = close;
                continue;
            }
            if (out == null) out = new StringBuilder(text.length() + 16);
            out.append(text, copied, open).append(value);
            copied = close + 1;
            scan = copied;
        }

        if (out == null) return text;
        return out.append(text, copied, text.length()).toString();
    }
}
