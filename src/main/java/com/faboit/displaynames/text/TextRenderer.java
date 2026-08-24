package com.faboit.displaynames.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a fully resolved string into a component.
 *
 * <p>Holds an optional bounded cache keyed by the resolved string. Players who share a rank
 * produce identical strings, so they share a single MiniMessage parse. Adventure components are
 * immutable, which makes sharing them across region threads safe.
 */
public final class TextRenderer {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Logger logger;
    private final LegacyColors.Mode legacyMode;
    private final int cacheLimit;
    private final Map<String, Component> cache;

    private final LongAdder parses = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private volatile boolean warned;

    public TextRenderer(Logger logger, LegacyColors.Mode legacyMode, int cacheLimit) {
        this.logger = logger;
        this.legacyMode = legacyMode;
        this.cacheLimit = Math.max(0, cacheLimit);
        this.cache = this.cacheLimit > 0 ? new ConcurrentHashMap<>(Math.min(this.cacheLimit, 256)) : null;
    }

    /** Converts legacy codes, parses MiniMessage and caches the result. */
    public Component render(String resolved) {
        if (resolved == null || resolved.isEmpty()) return Component.empty();

        if (cache == null) return parse(resolved);

        Component cached = cache.get(resolved);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }

        Component parsed = parse(resolved);
        // Cheap high-water mark instead of a full LRU: nametag strings churn slowly, and a
        // wipe costs one allocation-free clear() rather than per-entry bookkeeping.
        if (cache.size() >= cacheLimit) cache.clear();
        cache.put(resolved, parsed);
        return parsed;
    }

    private Component parse(String resolved) {
        parses.increment();
        String prepared = LegacyColors.convert(resolved, legacyMode);
        try {
            return miniMessage.deserialize(prepared);
        } catch (RuntimeException ex) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "Malformed MiniMessage in a nametag line, falling back to plain text. "
                        + "Further parse errors are not logged. Offending text: " + resolved, ex);
            }
            return Component.text(PlainTextComponentSerializer.plainText().serialize(Component.text(prepared)));
        }
    }

    public void invalidate() {
        if (cache != null) cache.clear();
    }

    public long parseCount() {
        return parses.sum();
    }

    public long cacheHitCount() {
        return cacheHits.sum();
    }

    public int cacheSize() {
        return cache == null ? 0 : cache.size();
    }
}
