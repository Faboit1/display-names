package com.faboit.displaynames.config;

import java.util.Locale;

/** How a nametag entity is kept above its player. */
public enum Anchor {

    /**
     * The entity is positioned above the head every tick and carries no translation.
     *
     * <p>A billboard rotates the transformation along with it, so any translation swings the text
     * around the entity's position on an arc of that translation's length - which is what makes a
     * {@code CENTER} billboard drift off the top of the head when you look down at someone. Putting
     * the height into the entity's own position instead leaves the translation at zero, so the
     * billboard pivots about the text itself and it stays put from every angle.
     *
     * <p>Costs one entity move per player per {@code follow-interval}.
     */
    FOLLOW,

    /**
     * The entity rides the player as a passenger.
     *
     * <p>Free: the client interpolates a passenger's position, so the server never moves the tag.
     * The catch is that a passenger sits at vanilla's mount anchor, roughly chest height, so the
     * rest of the height has to come from a translation - and with a {@code CENTER} billboard that
     * translation orbits. Pair this with {@code billboard: VERTICAL}, which has no pitch for the
     * offset to swing on.
     */
    MOUNT;

    public static Anchor parse(String raw, Anchor fallback) {
        if (raw == null) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
