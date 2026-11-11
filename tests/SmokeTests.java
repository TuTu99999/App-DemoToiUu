package com.example.notelab;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.TextView;
import android.widget.ListView;
import android.view.MotionEvent;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.lang.reflect.Field;

/** Device-side checks without JUnit/AndroidX dependencies. Uses isolated test files. */
public class SmokeTests extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            storageAndLock();
            searching();
            activityAndExport();
            result.putString("stream", report + "\nALL CHECKS PASSED\n");
            finish(-1, result);
        } catch (Throwable e) {
            StringWriter trace = new StringWriter(); e.printStackTrace(new PrintWriter(trace));
            result.putString("stream", report + "\nFAILED\n" + trace);
            finish(0, result);
        }
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void storageAndLock() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "checks-" + UUID.randomUUID());
        check(directory.mkdirs(), "Create isolated files");
        NoteStore store = new NoteStore(directory);
        check(store.load(s -> {}).notes.isEmpty(), "Empty initial store");
        List<Note> notes = Arrays.asList(new Note("1", "Tiếng Việt", "Nội dung UTF-8\nDòng hai"));
        NoteStore.Snapshot old = store.snapshot(notes);
        store.save(old, false, s -> {});
        check(store.load(s -> {}).notes.get(0).body.equals(notes.get(0).body), "Unicode round trip");
        NoteStore.Snapshot latest = store.snapshot(Arrays.asList(new Note("2", "Mới nhất", "Đã sửa")));
        CountDownLatch acquired = new CountDownLatch(1);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> save = writer.submit(() -> {
                try { store.save(latest, true, line -> { if (line.contains("ĐÃ VÀO KHÓA")) acquired.countDown(); }); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            check(acquired.await(5, TimeUnit.SECONDS), "Autosave acquires lock");
            long start = System.nanoTime();
            File backup = store.backup(latest, s -> {});
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            save.get(5, TimeUnit.SECONDS);
            check(elapsed >= 800, "Backup must wait while autosave holds the lock: " + elapsed);
            String content;
            try (InputStream in = new FileInputStream(backup)) { content = read(in); }
            check(new JSONObject(content).getJSONArray("notes").getJSONObject(0).getString("title").equals("Mới nhất"), "Backup is complete and current");
            store.save(old, false, s -> {});
            check(store.load(s -> {}).notes.get(0).id.equals("2"), "Stale save cannot overwrite new revision");
            NoteStore.Snapshot pending = store.snapshot(Arrays.asList(new Note("3", "Chưa debounce", "Bản mới")));
            store.backup(pending, s -> {});
            check(store.load(s -> {}).notes.get(0).id.equals("3"), "Backup includes not-yet-debounced edits");
            // Rollback when a write is interrupted must preserve the previously committed data.
            Future<?> interrupted = writer.submit(() -> {
                Thread.currentThread().interrupt();
                try { store.save(store.snapshot(notes), true, s -> {}); throw new AssertionError("Expected interruption"); }
                catch (InterruptedException expected) { Thread.interrupted(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            interrupted.get(5, TimeUnit.SECONDS);
            check(store.load(s -> {}).notes.get(0).id.equals("3"), "Atomic rollback preserves data");
            report.append("PASS storage: UTF-8, backup lock, pending edits, stale writes, rollback\n");
        } finally { writer.shutdownNow(); store.writes.shutdownNow(); }
    }
    private void searching() throws Exception {
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < 37; i++) notes.add(new Note("" + i, i % 3 == 0 ? "JAVA" : "Khác", i % 5 == 0 ? "Học java" : "Nội dung"));
        for (int workers = 1; workers <= 16; workers++) {
            for (int limit : new int[]{1, 7, 37, 100}) {
                NoteSearch.Result result = NoteSearch.find(notes, " Java ", workers, limit, s -> {});
                List<String> expected = new ArrayList<>(), actual = new ArrayList<>();
                for (int i = 0; i < Math.min(limit, notes.size()); i++)
                    if (i % 3 == 0 || i % 5 == 0) expected.add("" + i);
                for (Note note : result.matches) actual.add(note.id);
                check(actual.equals(expected), "Search coverage/order: workers=" + workers + ", limit=" + limit);
                check(result.workers == Math.min(workers, Math.min(limit, notes.size())), "No idle extra workers");
            }
        }
        check(NoteSearch.find(Collections.emptyList(), "java", 16, 10, s -> {}).workers == 0, "Empty data");
        CountDownLatch entered = new CountDownLatch(1), block = new CountDownLatch(1);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        Future<?> task = coordinator.submit(() -> {
            try {
                NoteSearch.find(notes, "java", 4, 37, s -> {
                    entered.countDown();
                    try { block.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            } catch (Exception expected) { /* cancellation */ }
        });
        check(entered.await(5, TimeUnit.SECONDS), "Workers started");
        task.cancel(true); coordinator.shutdownNow();
        check(coordinator.awaitTermination(5, TimeUnit.SECONDS), "Search cancellation terminates coordinator");
        block.countDown();
        report.append("PASS search: all 1–16 workers, N/all, no duplicates, order, empty input, cancellation\n");
    }
    private void activityAndExport() throws Exception {
        Field singleton = NoteStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        File directory = new File(getTargetContext().getCacheDir(), "ui-checks-" + UUID.randomUUID());
        check(directory.mkdirs(), "Create isolated activity database");
        NoteStore isolated = new NoteStore(directory);
        List<Note> fixtures = new ArrayList<>();
        for (int i = 0; i < 40; i++) fixtures.add(new Note("test-" + i, "Ghi chú " + i, "Nội dung thử cuộn"));
        isolated.save(isolated.snapshot(fixtures), false, s -> {});
        singleton.set(null, isolated);
        MainActivity activity = null;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity screen = activity;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            boolean[] loaded = {false};
            do {
                runOnMainSync(() -> loaded[0] = screen.findViewById(R.id.add).isEnabled());
                if (!loaded[0]) Thread.sleep(30);
            } while (!loaded[0] && SystemClock.elapsedRealtime() < deadline);
            check(loaded[0], "Activity loads through AsyncTask");
            String[] log = {""};
            runOnMainSync(() -> log[0] = ((TextView) screen.findViewById(R.id.log)).getText().toString());
            check(log[0].contains("[main] ASYNC · onPostExecute"), "AsyncTask UI callback uses main thread");
            check(log[0].contains("doInBackground") && !log[0].contains("[main] ASYNC · doInBackground"), "Read uses background thread");
            File exported = new File(getTargetContext().getCacheDir(), "export-check.txt");
            Note note = new Note("export", "Ghi chú tiếng Việt", "Xin chào!\nDòng thứ hai.");
            runOnMainSync(() -> screen.exportNote(note, Uri.fromFile(exported)));
            boolean[] done = {false};
            deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                runOnMainSync(() -> done[0] = ((TextView) screen.findViewById(R.id.status)).getText().toString().startsWith("Đã xuất"));
                if (!done[0]) Thread.sleep(30);
            } while (!done[0] && SystemClock.elapsedRealtime() < deadline);
            check(done[0], "Handler reports export completion");
            try (InputStream in = new FileInputStream(exported)) { check(read(in).equals(note.text()), "Exported UTF-8 contents"); }
            runOnMainSync(() -> log[0] = ((TextView) screen.findViewById(R.id.log)).getText().toString());
            check(log[0].contains("[Export-note] EXPORT"), "Raw Thread writes file");
            check(log[0].contains("[main] HANDLER"), "Handler updates on main thread");
            report.append("PASS activity: AsyncTask background/UI callbacks, Thread export, Handler, UTF-8 file\n");
            apiWhileScrolling(screen);
        } finally {
            if (activity != null) {
                final MainActivity closing = activity;
                runOnMainSync(closing::finish);
                awaitUi("Activity finishes before closing its test database", closing::isDestroyed);
            }
            isolated.writes.shutdown();
            isolated.writes.awaitTermination(5, TimeUnit.SECONDS);
            singleton.set(null, previous);
        }
    }

    private void apiWhileScrolling(MainActivity screen) throws Exception {
        android.content.SharedPreferences preferences = screen.getPreferences(0);
        String original = preferences.getString("apiUrl", null);
        ExecutorService server = Executors.newSingleThreadExecutor();
        CountDownLatch received = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(15000);
            Future<?> backend = server.submit(() -> {
                try {
                    for (int request = 0; request < 2; request++) {
                        try (Socket client = listener.accept()) {
                            client.setSoTimeout(5000);
                            BufferedReader headers = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                            String line;
                            while ((line = headers.readLine()) != null && !line.isEmpty()) { /* HTTP headers */ }
                            if (request == 0) { received.countDown(); Thread.sleep(6000); }
                            byte[] body = "{\"message\":\"Gợi ý tiếng Việt\"}".getBytes(StandardCharsets.UTF_8);
                            String response = request == 0 ? "200 OK" : "503 Service Unavailable";
                            OutputStream output = client.getOutputStream();
                            output.write(("HTTP/1.1 " + response + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            output.write(body); output.flush();
                        }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            preferences.edit().putString("apiUrl", "http://127.0.0.1:" + listener.getLocalPort() + "/suggestion").commit();
            long start = SystemClock.elapsedRealtime();
            runOnMainSync(() -> screen.findViewById(R.id.api).performClick());
            check(received.await(5, TimeUnit.SECONDS), "Actual HTTP request reaches delayed backend");
            boolean[] waiting = {false};
            int[] bounds = new int[4];
            runOnMainSync(() -> {
                waiting[0] = !screen.findViewById(R.id.api).isEnabled();
                ListView list = screen.findViewById(R.id.list);
                list.getLocationOnScreen(bounds);
                bounds[2] = list.getWidth(); bounds[3] = list.getHeight();
            });
            check(waiting[0], "API is still waiting for backend");
            // Real touch gesture while the server is sleeping.
            long down = SystemClock.uptimeMillis();
            for (int step = 0; step <= 12; step++) {
                int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                float x = bounds[0] + bounds[2] / 2f;
                float y = bounds[1] + bounds[3] * (0.85f - step * 0.055f);
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
                sendPointerSync(event); event.recycle(); Thread.sleep(20);
            }
            awaitUi("List scrolls while API is waiting", () ->
                    ((ListView) screen.findViewById(R.id.list)).getFirstVisiblePosition() > 0
                            && !screen.findViewById(R.id.api).isEnabled());
            awaitUi("Coroutine returns API result", () -> screen.findViewById(R.id.api).isEnabled());
            check(SystemClock.elapsedRealtime() - start >= 5900, "Backend delay exceeds five seconds");
            String[] log = {""}, result = {""};
            runOnMainSync(() -> {
                result[0] = ((TextView) screen.findViewById(R.id.apiResult)).getText().toString();
                log[0] = ((TextView) screen.findViewById(R.id.log)).getText().toString();
            });
            check(result[0].contains("Gợi ý tiếng Việt"), "UTF-8 API response is displayed");
            check(log[0].contains("[main] API · nhận kết quả"), "Coroutine resumes on Main");
            check(log[0].contains("API · đang gọi HTTP") && !log[0].contains("[main] API · đang gọi HTTP"), "HTTP runs on IO dispatcher");
            runOnMainSync(() -> screen.findViewById(R.id.api).performClick());
            awaitUi("HTTP failure restores API button", () -> screen.findViewById(R.id.api).isEnabled()
                    && ((TextView) screen.findViewById(R.id.apiResult)).getText().toString().contains("HTTP 503"));
            backend.get(5, TimeUnit.SECONDS);
            report.append("PASS API: real HTTP >5s, touch scrolling while pending, IO/Main, UTF-8, HTTP failure and retry\n");
        } finally {
            android.content.SharedPreferences.Editor edit = preferences.edit();
            if (original == null) edit.remove("apiUrl"); else edit.putString("apiUrl", original);
            edit.commit(); server.shutdownNow();
        }
    }

    private void awaitUi(String label, BooleanSupplier test) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean[] done = {false};
        do {
            runOnMainSync(() -> done[0] = test.getAsBoolean());
            if (!done[0]) Thread.sleep(40);
        } while (!done[0] && SystemClock.elapsedRealtime() < deadline);
        check(done[0], label + " timed out");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
