package com.faboit.displaynames.nametag;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A coarse, lock-free population grid used to answer "can anyone see this player?".
 *
 * <p>Scanning every online player would be O(n) per refresh - O(n^2) per interval across the
 * server. Instead each player keeps a counter in a grid cell, and the question is answered by
 * summing the 3x3 neighbourhood around the player: nine map lookups, independent of player count.
 *
 * <p>The cell size is never smaller than the tag's render distance, so a neighbourhood always
 * covers at least one full render distance in every direction. That makes the answer a safe
 * over-approximation: it can report a viewer who is slightly too far away (harmless - we just
 * refresh anyway) but never misses one who can actually see the tag.
 *
 * <p>Every mutation is a single atomic {@code ConcurrentHashMap} operation, so region threads
 * update it concurrently without synchronisation.
 */
public final class ViewerIndex {

    private final Map<UUID, ConcurrentHashMap<Long, Integer>> worlds = new ConcurrentHashMap<>();
    private final int shift;
    private final int cellSize;

    public ViewerIndex(int requestedCellSize) {
        int size = Integer.highestOneBit(Math.max(16, requestedCellSize));
        if (size < requestedCellSize) size <<= 1; // round up to a power of two so we can shift
        this.cellSize = size;
        this.shift = Integer.numberOfTrailingZeros(size);
    }

    public int cellSize() {
        return cellSize;
    }

    /** Packs a block position into the key of the cell containing it. */
    public long cellAt(double x, double z) {
        long cellX = (long) Math.floor(x) >> shift;
        long cellZ = (long) Math.floor(z) >> shift;
        return (cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public void add(UUID world, long cell) {
        worlds.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>()).merge(cell, 1, Integer::sum);
    }

    public void remove(UUID world, long cell) {
        ConcurrentHashMap<Long, Integer> cells = worlds.get(world);
        if (cells == null) return;
        cells.computeIfPresent(cell, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    public void move(UUID fromWorld, long fromCell, UUID toWorld, long toCell) {
        if (fromWorld.equals(toWorld) && fromCell == toCell) return;
        remove(fromWorld, fromCell);
        add(toWorld, toCell);
    }

    /**
     * Short-circuiting form of {@link #populationAround}: stops looking as soon as enough players
     * have been found, which is the common case when anybody is nearby at all.
     */
    public boolean hasAtLeast(UUID world, long cell, int minimum) {
        if (minimum <= 0) return true;
        ConcurrentHashMap<Long, Integer> cells = worlds.get(world);
        if (cells == null || cells.isEmpty()) return false;

        long centreX = cell >> 32;
        long centreZ = (int) cell;

        int total = 0;
        for (long dx = -1; dx <= 1; dx++) {
            long x = (centreX + dx) << 32;
            for (long dz = -1; dz <= 1; dz++) {
                Integer count = cells.get(x | ((centreZ + dz) & 0xFFFFFFFFL));
                if (count != null && (total += count) >= minimum) return true;
            }
        }
        return false;
    }

    /** Number of tracked players in the 3x3 neighbourhood centred on {@code cell}. */
    public int populationAround(UUID world, long cell) {
        ConcurrentHashMap<Long, Integer> cells = worlds.get(world);
        if (cells == null || cells.isEmpty()) return 0;

        long centreX = cell >> 32;
        long centreZ = (int) cell; // sign-extends the low half back to a signed cell coordinate

        int total = 0;
        for (long dx = -1; dx <= 1; dx++) {
            long x = (centreX + dx) << 32;
            for (long dz = -1; dz <= 1; dz++) {
                Integer count = cells.get(x | ((centreZ + dz) & 0xFFFFFFFFL));
                if (count != null) total += count;
            }
        }
        return total;
    }

    public void clear() {
        worlds.clear();
    }
}
