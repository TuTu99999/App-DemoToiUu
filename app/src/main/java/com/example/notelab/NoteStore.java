package com.example.notelab;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Autosave, reload and backup share one lock and one process-wide store. */
final class NoteStore {
    private static NoteStore instance;
    private final Object fileLock = new Object();
    private final AtomicFile file, backup;
    private final AtomicLong sequence = new AtomicLong();
    final ExecutorService writes = Executors.newSingleThreadExecutor(task -> new Thread(task, "AutoSave"));
    private long diskRevision = -1;

    static synchronized NoteStore get(Context context) {
        if (instance == null) instance = new NoteStore(context.getFilesDir());
        return instance;
    }

    NoteStore(File directory) {
        file = new AtomicFile(new File(directory, "notes.json"));
        backup = new AtomicFile(new File(directory, "notes-backup.json"));
    }

    static final class Snapshot {
        final long revision;
        final List<Note> notes;
        Snapshot(long revision, List<Note> notes) {
            this.revision = revision;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }
    }

    Snapshot snapshot(List<Note> notes) { return new Snapshot(sequence.incrementAndGet(), notes); }

    Snapshot load(Consumer<String> log) throws Exception {
        synchronized (fileLock) {
            log.accept("LOAD · đọc notes.json");
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
                return new Snapshot(0, Collections.emptyList());
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            List<Note> notes = new ArrayList<>();
            JSONArray array = root.getJSONArray("notes");
            for (int i = 0; i < array.length(); i++) notes.add(Note.from(array.getJSONObject(i)));
            diskRevision = root.getLong("revision");
            sequence.updateAndGet(value -> Math.max(value, diskRevision));
            return new Snapshot(diskRevision, notes);
        }
    }

    void save(Snapshot snapshot, boolean slow, Consumer<String> log) throws Exception {
        log.accept("SAVE · chờ khóa · v" + snapshot.revision);
        synchronized (fileLock) { saveLocked(snapshot, slow, log); }
    }

    private void saveLocked(Snapshot snapshot, boolean slow, Consumer<String> log) throws Exception {
        if (snapshot.revision <= diskRevision) return;
        log.accept("SAVE · ĐÃ VÀO KHÓA · v" + snapshot.revision);
        JSONArray array = new JSONArray();
        for (Note note : snapshot.notes) array.put(note.json());
        byte[] bytes = new JSONObject().put("revision", snapshot.revision).put("notes", array)
                .toString().getBytes(StandardCharsets.UTF_8);
        writeAtomic(file, bytes, slow);
        diskRevision = snapshot.revision;
        log.accept("SAVE · ghi xong, nhả khóa · v" + snapshot.revision);
    }

    File backup(Snapshot current, Consumer<String> log) throws Exception {
        log.accept("BACKUP · chờ khóa");
        synchronized (fileLock) {
            log.accept("BACKUP · ĐÃ VÀO KHÓA");
            saveLocked(current, false, log);
            writeAtomic(backup, file.readFully(), false);
            log.accept("BACKUP · bản sao đầy đủ, nhả khóa");
            return backup.getBaseFile();
        }
    }

    private static void writeAtomic(AtomicFile target, byte[] bytes, boolean slow) throws Exception {
        FileOutputStream out = null;
        try {
            out = target.startWrite();
            int middle = bytes.length / 2;
            out.write(bytes, 0, middle);
            if (slow) Thread.sleep(1500); // Only enabled for the lock demonstration.
            out.write(bytes, middle, bytes.length - middle);
            target.finishWrite(out);
        } catch (Exception e) {
            if (out != null) target.failWrite(out);
            throw e;
        }
    }
}
