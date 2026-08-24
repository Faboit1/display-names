package com.faboit.displaynames.text;

/**
 * Converts legacy colour codes into MiniMessage tags.
 *
 * <p>Placeholder expansions (LuckPerms prefixes, Vault, chat plugins) still emit legacy
 * {@code §} codes, which MiniMessage does not understand. Rather than pre-processing every
 * string, {@link #convert} returns the input untouched unless a marker character is actually
 * present, so the common case costs a single {@code indexOf}.
 */
public final class LegacyColors {

    /** Which marker characters are translated. */
    public enum Mode {
        NONE(false, false),
        SECTION(true, false),
        AMPERSAND(false, true),
        BOTH(true, true);

        private final boolean section;
        private final boolean ampersand;

        Mode(boolean section, boolean ampersand) {
            this.section = section;
            this.ampersand = ampersand;
        }

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) return fallback;
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    private static final char SECTION = '§';
    private static final char AMPERSAND = '&';

    /** Indexed by code character; {@code null} means "not a legacy code". */
    private static final String[] TAGS = new String[128];

    static {
        put('0', "<black>");
        put('1', "<dark_blue>");
        put('2', "<dark_green>");
        put('3', "<dark_aqua>");
        put('4', "<dark_red>");
        put('5', "<dark_purple>");
        put('6', "<gold>");
        put('7', "<gray>");
        put('8', "<dark_gray>");
        put('9', "<blue>");
        put('a', "<green>");
        put('b', "<aqua>");
        put('c', "<red>");
        put('d', "<light_purple>");
        put('e', "<yellow>");
        put('f', "<white>");
        put('k', "<obfuscated>");
        put('l', "<bold>");
        put('m', "<strikethrough>");
        put('n', "<underlined>");
        put('o', "<italic>");
        put('r', "<reset>");
    }

    private static void put(char lower, String tag) {
        TAGS[lower] = tag;
        TAGS[Character.toUpperCase(lower)] = tag;
    }

    private LegacyColors() {
    }

    /**
     * @return {@code input} with legacy codes rewritten as MiniMessage tags, or the very same
     *         instance when there was nothing to rewrite
     */
    public static String convert(String input, Mode mode) {
        if (mode == Mode.NONE || input == null || input.isEmpty()) return input;

        boolean section = mode.section && input.indexOf(SECTION) >= 0;
        boolean ampersand = mode.ampersand && input.indexOf(AMPERSAND) >= 0;
        if (!section && !ampersand) return input;

        int length = input.length();
        StringBuilder out = new StringBuilder(length + 16);
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            boolean marker = (section && c == SECTION) || (ampersand && c == AMPERSAND);
            if (!marker || i + 1 >= length) {
                out.append(c);
                i++;
                continue;
            }

            char code = input.charAt(i + 1);

            // §x§r§r§g§g§b§b -> <#rrggbb>
            if ((code == 'x' || code == 'X') && i + 13 < length) {
                String hex = readHexSequence(input, i, c);
                if (hex != null) {
                    out.append('<').append('#').append(hex).append('>');
                    i += 14;
                    continue;
                }
            }

            String tag = code < 128 ? TAGS[code] : null;
            if (tag != null) {
                out.append(tag);
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Reads the 6 hex digits of a {@code §x§r§r§g§g§b§b} run starting at {@code start}. */
    private static String readHexSequence(String input, int start, char marker) {
        char[] hex = new char[6];
        for (int n = 0; n < 6; n++) {
            int base = start + 2 + (n * 2);
            if (input.charAt(base) != marker) return null;
            char digit = input.charAt(base + 1);
            if (Character.digit(digit, 16) < 0) return null;
            hex[n] = digit;
        }
        return new String(hex);
    }
}
