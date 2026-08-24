package com.faboit.displaynames.nametag;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerIndexTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    @Test
    void cellSizeIsRoundedUpToAPowerOfTwo() {
        assertEquals(128, new ViewerIndex(128).cellSize());
        assertEquals(128, new ViewerIndex(100).cellSize());
        assertEquals(16, new ViewerIndex(1).cellSize());
    }

    @Test
    void negativeCoordinatesGetDistinctCells() {
        ViewerIndex index = new ViewerIndex(128);
        assertEquals(index.cellAt(-1, -1), index.cellAt(-128, -128));
        assertNotEquals(index.cellAt(-1, -1), index.cellAt(0, 0));
        assertNotEquals(index.cellAt(-1, 5), index.cellAt(5, -1));
    }

    @Test
    void countsNeighboursAcrossNegativeCellBoundaries() {
        ViewerIndex index = new ViewerIndex(128);
        long centre = index.cellAt(-5, -5);
        index.add(WORLD, centre);
        index.add(WORLD, index.cellAt(5, 5));       // +1/+1 cell
        index.add(WORLD, index.cellAt(-200, -200)); // -1/-1 cell
        index.add(WORLD, index.cellAt(-400, -5));   // two cells away, out of range

        assertEquals(3, index.populationAround(WORLD, centre));
    }

    @Test
    void worldsAreIsolated() {
        ViewerIndex index = new ViewerIndex(128);
        long cell = index.cellAt(0, 0);
        index.add(OTHER_WORLD, cell);
        assertEquals(0, index.populationAround(WORLD, cell));
        assertEquals(1, index.populationAround(OTHER_WORLD, cell));
    }

    @Test
    void movingBetweenCellsKeepsTheTotalStable() {
        ViewerIndex index = new ViewerIndex(128);
        long from = index.cellAt(0, 0);
        long to = index.cellAt(1000, 1000);
        index.add(WORLD, from);
        index.move(WORLD, from, WORLD, to);

        assertEquals(0, index.populationAround(WORLD, index.cellAt(500, 500)));
        assertEquals(1, index.populationAround(WORLD, to));
    }

    @Test
    void movingWithinTheSameCellIsANoOp() {
        ViewerIndex index = new ViewerIndex(128);
        long cell = index.cellAt(0, 0);
        index.add(WORLD, cell);
        index.move(WORLD, cell, WORLD, cell);
        assertEquals(1, index.populationAround(WORLD, cell));
    }

    @Test
    void removalDropsBackToZero() {
        ViewerIndex index = new ViewerIndex(128);
        long cell = index.cellAt(64, 64);
        index.add(WORLD, cell);
        index.add(WORLD, cell);
        index.remove(WORLD, cell);
        assertEquals(1, index.populationAround(WORLD, cell));
        index.remove(WORLD, cell);
        assertEquals(0, index.populationAround(WORLD, cell));
        index.remove(WORLD, cell); // over-removal must not go negative
        assertEquals(0, index.populationAround(WORLD, cell));
    }

    @Test
    void neighbourhoodCoversAtLeastOneCellInEveryDirection() {
        // The skip-when-unwatched optimisation is only safe if a player standing anywhere in the
        // centre cell sees everyone within `cellSize` blocks of them.
        ViewerIndex index = new ViewerIndex(128);
        long centre = index.cellAt(0, 0);           // player at the very corner of their cell
        index.add(WORLD, index.cellAt(-128, -128)); // exactly cellSize away, diagonally
        index.add(WORLD, index.cellAt(127, 127));
        assertEquals(2, index.populationAround(WORLD, centre));
    }

    @Test
    void hasAtLeastAgreesWithTheFullCount() {
        ViewerIndex index = new ViewerIndex(128);
        long centre = index.cellAt(0, 0);
        assertFalse(index.hasAtLeast(WORLD, centre, 1));

        index.add(WORLD, centre);
        assertTrue(index.hasAtLeast(WORLD, centre, 1));
        assertFalse(index.hasAtLeast(WORLD, centre, 2));

        index.add(WORLD, index.cellAt(-100, 200)); // the -1/+1 cell, still adjacent
        assertTrue(index.hasAtLeast(WORLD, centre, 2));
        assertFalse(index.hasAtLeast(WORLD, centre, 3));
        assertFalse(index.hasAtLeast(OTHER_WORLD, centre, 1));
    }

    @Test
    void concurrentUpdatesFromManyThreadsStayConsistent() throws Exception {
        ViewerIndex index = new ViewerIndex(128);
        long cell = index.cellAt(0, 0);
        int threads = 8;
        int perThread = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    index.add(WORLD, cell);
                    index.remove(WORLD, cell);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, index.populationAround(WORLD, cell));
    }
}
