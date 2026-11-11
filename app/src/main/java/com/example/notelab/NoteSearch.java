package com.example.notelab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class NoteSearch {
    static final int MAX_WORKERS = 16;
    static final class Result {
        final List<Note> matches;
        final int scanned, workers;
        final long nanos;
        Result(List<Note> matches, int scanned, int workers, long nanos) {
            this.matches = matches; this.scanned = scanned; this.workers = workers; this.nanos = nanos;
        }
    }

    static Result find(List<Note> notes, String query, int requestedWorkers, int limit,
                       Consumer<String> log) throws Exception {
        if (requestedWorkers < 1 || requestedWorkers > MAX_WORKERS || limit < 1 || query.trim().isEmpty())
            throw new IllegalArgumentException("Workers 1–16, số note > 0 và từ khóa không rỗng.");
        long start = System.nanoTime();
        int size = Math.min(limit, notes.size());
        if (size == 0) return new Result(new ArrayList<>(), 0, 0, System.nanoTime() - start);
        int workers = Math.min(requestedWorkers, size);
        String needle = query.trim().toLowerCase(Locale.ROOT);
        AtomicInteger number = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers,
                task -> new Thread(task, "Search-" + number.incrementAndGet()));
        try {
            List<Future<List<Note>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final int from = i * size / workers, to = (i + 1) * size / workers;
                futures.add(pool.submit(() -> {
                    log.accept("SEARCH · note " + (from + 1) + "–" + to);
                    List<Note> matches = new ArrayList<>();
                    for (int n = from; n < to; n++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        Note note = notes.get(n);
                        if (note.title.toLowerCase(Locale.ROOT).contains(needle)
                                || note.body.toLowerCase(Locale.ROOT).contains(needle)) matches.add(note);
                    }
                    log.accept("SEARCH · xong, " + matches.size() + " kết quả");
                    return matches;
                }));
            }
            List<Note> matches = new ArrayList<>();
            for (Future<List<Note>> future : futures) matches.addAll(future.get());
            return new Result(matches, size, workers, System.nanoTime() - start);
        } finally {
            pool.shutdownNow();
        }
    }
}
