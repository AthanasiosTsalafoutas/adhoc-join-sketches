package com.adhocjoin.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shared plumbing of the experiment drivers: independent master seeds and a fixed thread pool. */
public final class Runs {
    private Runs() {
    }

    /** {@code count} full 64-bit master seeds from one generator seeded with {@code seed}. */
    public static long[] masterSeeds(long seed, int count) {
        SplittableRandom rnd = new SplittableRandom(seed);
        long[] masters = new long[count];
        for (int i = 0; i < count; i++) {
            masters[i] = rnd.nextLong();
        }
        return masters;
    }

    /** Runs every task on {@code threads} threads and waits for all of them; the first failure is rethrown. */
    public static void parallel(int threads, List<? extends Callable<?>> tasks) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Callable<?> task : tasks) {
                futures.add(ex.submit(task));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            ex.shutdownNow();
        }
    }
}
