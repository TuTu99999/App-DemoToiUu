package com.example.notelab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Minimal one-screen app illustrating five threading cases. */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private static final int EXPORT = 10, DEFAULT_WORKERS = 4;
    private static final String DEFAULT_API = "http://10.0.2.2:8765/suggestion";
    private static final String PREF_API = "apiUrl", PREF_WORKERS = "labWorkers",
            PREF_LIMIT = "labLimit", PREF_SLOW = "labSlowSave";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Note> notes = new ArrayList<>(), visible = new ArrayList<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final ExecutorService searches = Executors.newSingleThreadExecutor(r -> new Thread(r, "Search-coordinator"));
    private final ExecutorService backups = Executors.newSingleThreadExecutor(r -> new Thread(r, "Backup"));
    private NoteStore store;
    private ApiDemo api;
    private NoteStore.Snapshot current;
    private ArrayAdapter<Note> adapter;
    private AsyncTask<Void, Void, NoteStore.Snapshot> loader;
    private Future<?> searchTask;
    private EditText query;
    private TextView status, journal, empty;
    private AlertDialog editor;
    private Note pendingExport;
    private boolean alive = true, ready;
    private int searchRun;
    private long lastScheduledRevision = -1;
    private final Runnable saveLater = this::saveNow;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        View root = view(R.id.root);
        int padding = dp(16);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        store = NoteStore.get(this);
        api = new ApiDemo(this::log);
        query = view(R.id.query);
        status = view(R.id.status); journal = view(R.id.log); empty = view(R.id.empty);
        ListView list = view(R.id.list);
        adapter = new ArrayAdapter<Note>(this, R.layout.row_note, visible) {
            @Override public View getView(int position, View row, ViewGroup parent) {
                if (row == null) row = getLayoutInflater().inflate(R.layout.row_note, parent, false);
                Note note = getItem(position);
                ((TextView) row.findViewById(R.id.title)).setText(note.label());
                String preview = note.body.isEmpty() ? "Chạm để viết nội dung…" : note.body.substring(0, Math.min(180, note.body.length()));
                ((TextView) row.findViewById(R.id.body)).setText(preview);
                return row;
            }
        };
        list.setAdapter(adapter); list.setEmptyView(empty);
        list.setOnItemClickListener((parent, v, position, id) -> edit(visible.get(position)));
        view(R.id.add).setOnClickListener(v -> {
            Note note = new Note(UUID.randomUUID().toString(), "", "");
            notes.add(0, note); changed(); edit(note);
        });
        view(R.id.backup).setOnClickListener(v -> backup());
        view(R.id.api).setOnClickListener(v -> callApi());
        view(R.id.search).setOnClickListener(v -> search());
        view(R.id.more).setOnClickListener(this::menu);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search(); return true;
        });
        query.addTextChangedListener(watcher(() -> { cancelSearch(); if (ready) showAll(); }));
        if (state != null && state.containsKey("exportId"))
            pendingExport = new Note(state.getString("exportId"), state.getString("exportTitle"), state.getString("exportBody"));
        load();
    }

    // CASE 1: AsyncTask reads storage in the background and returns to Main.
    private void load() {
        ready = false; setBusy(true); setActions(false); cancelSearch();
        status.setText("Đang đọc ghi chú…");
        Future<?> barrier = store.writes.submit(() -> {});
        loader = new AsyncTask<Void, Void, NoteStore.Snapshot>() {
            private Exception error;
            @Override protected void onPreExecute() { log("ASYNC · onPreExecute"); }
            @Override protected NoteStore.Snapshot doInBackground(Void... ignored) {
                try {
                    barrier.get(); log("ASYNC · doInBackground");
                    return store.load(MainActivity.this::log);
                } catch (Exception e) { error = e; return null; }
            }
            @Override protected void onPostExecute(NoteStore.Snapshot data) {
                if (!alive) return;
                loader = null; log("ASYNC · onPostExecute"); setBusy(false);
                if (data == null) { failure("Đọc ghi chú", error); return; }
                current = data; lastScheduledRevision = data.revision;
                notes.clear(); notes.addAll(data.notes);
                ready = true; setActions(true); showAll();
            }
        };
        loader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void edit(Note original) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText title = new EditText(this), body = new EditText(this);
        title.setHint("Tiêu đề"); title.setSingleLine(); title.setTextSize(20); title.setText(original.title);
        body.setHint("Viết điều bạn muốn nhớ…"); body.setTextSize(16); body.setMinLines(5); body.setMaxLines(10);
        body.setGravity(Gravity.TOP);
        body.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.setText(original.body);
        TextView hint = new TextView(this);
        hint.setText("Tự động lưu khi bạn ngừng gõ."); hint.setTextSize(12);
        Button backupButton = new Button(this);
        backupButton.setText("Sao lưu ngay"); backupButton.setAllCaps(false);
        backupButton.setOnClickListener(v -> backup());
        form.addView(title); form.addView(body); form.addView(hint); form.addView(backupButton);
        TextWatcher changes = watcher(() -> {
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id.equals(original.id)) {
                    notes.set(i, new Note(original.id, title.getText().toString(), body.getText().toString()));
                    changed(); break;
                }
            }
        });
        title.addTextChangedListener(changes); body.addTextChangedListener(changes);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        editor = new AlertDialog.Builder(this).setTitle("Ghi chú").setView(scroll)
                .setPositiveButton("Xong", null).setNeutralButton("Xuất .txt", null).setNegativeButton("Xóa", null).create();
        editor.setOnShowListener(ignored -> {
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                pendingExport = new Note(original.id, title.getText().toString(), body.getText().toString());
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain").putExtra(Intent.EXTRA_TITLE, filename(pendingExport.label()) + ".txt");
                startActivityForResult(intent, EXPORT);
            });
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    new AlertDialog.Builder(this).setMessage("Xóa ghi chú này?").setNegativeButton("Giữ lại", null)
                            .setPositiveButton("Xóa", (d, which) -> {
                                notes.removeIf(note -> note.id.equals(original.id)); changed(); editor.dismiss();
                            }).show());
        });
        editor.setOnDismissListener(ignored -> { if (alive) saveNow(); });
        editor.show();
    }

    private void changed() {
        cancelSearch(); current = store.snapshot(notes); showAll();
        status.setText("Chưa lưu · đang chờ bạn ngừng gõ…");
        main.removeCallbacks(saveLater); main.postDelayed(saveLater, 600);
    }

    private void saveNow() {
        main.removeCallbacks(saveLater);
        if (!ready || current == null || lastScheduledRevision == current.revision) return;
        NoteStore.Snapshot snapshot = current;
        lastScheduledRevision = snapshot.revision;
        boolean demoSlow = getPreferences(MODE_PRIVATE).getBoolean(PREF_SLOW, false);
        store.writes.execute(() -> {
            try {
                store.save(snapshot, demoSlow, this::log);
                ui(() -> {
                    if (current.revision == snapshot.revision && searchTask == null)
                        status.setText(notes.size() + " ghi chú · đã lưu trên máy");
                });
            } catch (Exception e) { ui(() -> { lastScheduledRevision = -1; failure("Lưu ghi chú", e); }); }
        });
    }

    // CASE 2: a raw Thread writes UTF-8; Handler delivers the UI result.
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT) return;
        Note note = pendingExport; pendingExport = null;
        if (result == RESULT_OK && data != null && data.getData() != null && note != null)
            exportNote(note, data.getData());
    }

    void exportNote(Note note, Uri destination) {
        status.setText("Đang xuất “" + note.label() + "”…");
        new Thread(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new java.io.IOException("Không mở được nơi lưu.");
                log("EXPORT · ghi file UTF-8"); out.write(note.text().getBytes(StandardCharsets.UTF_8)); out.flush();
            } catch (Exception e) { ui(() -> failure("Xuất file", e)); return; }
            main.post(() -> {
                if (!alive) return;
                log("HANDLER · thông báo xuất thành công");
                status.setText("Đã xuất “" + note.label() + "” thành .txt"); toast("Xuất file thành công");
            });
        }, "Export-note").start();
    }

    // CASE 3: autosave and backup both use NoteStore.fileLock.
    private void backup() {
        saveNow();
        NoteStore.Snapshot snapshot = current;
        status.setText("Đang sao lưu…");
        backups.execute(() -> {
            try {
                java.io.File file = store.backup(snapshot, this::log);
                ui(() -> { status.setText("Đã sao lưu tất cả ghi chú trên máy"); toast("Đã tạo " + file.getName()); });
            } catch (Exception e) { ui(() -> failure("Sao lưu", e)); }
        });
    }

    // CASE 4: each worker searches a range; results are merged in order.
    private void search() {
        String needle = query.getText().toString().trim();
        if (needle.isEmpty()) { cancelSearch(); showAll(); return; }
        int count = Integer.MAX_VALUE;
        String limitPref = getPreferences(MODE_PRIVATE).getString(PREF_LIMIT, "").trim();
        if (!limitPref.isEmpty()) {
            try {
                count = Integer.parseInt(limitPref);
                if (count < 1) count = Integer.MAX_VALUE;
            } catch (NumberFormatException ignored) { count = Integer.MAX_VALUE; }
        }
        int requested = Math.max(1, Math.min(NoteSearch.MAX_WORKERS,
                getPreferences(MODE_PRIVATE).getInt(PREF_WORKERS, DEFAULT_WORKERS)));
        cancelSearch();
        int run = searchRun, maximum = count;
        List<Note> snapshot = new ArrayList<>(notes);
        setBusy(true); status.setText("Đang tìm “" + needle + "”…");
        searchTask = searches.submit(() -> {
            try {
                NoteSearch.Result result = NoteSearch.find(snapshot, needle, requested, maximum, this::log);
                ui(() -> {
                    if (run != searchRun) return;
                    searchTask = null; setBusy(false);
                    visible.clear(); visible.addAll(result.matches); adapter.notifyDataSetChanged();
                    empty.setText("Không tìm thấy ghi chú phù hợp.");
                    boolean labVisible = view(R.id.demo).getVisibility() == View.VISIBLE;
                    status.setText(labVisible
                            ? String.format(Locale.US, "%d/%d note · %d workers · %.2f ms",
                                    result.matches.size(), result.scanned, result.workers, result.nanos / 1_000_000.0)
                            : result.matches.size() + " ghi chú phù hợp");
                    log("SEARCH · gộp và hiển thị kết quả");
                });
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { ui(() -> {
                if (run == searchRun) { searchTask = null; setBusy(false); failure("Tìm kiếm", e); }
            }); }
        });
    }

    private void cancelSearch() {
        searchRun++;
        if (searchTask != null) { searchTask.cancel(true); searchTask = null; }
        if (ready) setBusy(false);
    }

    private void showAll() {
        visible.clear(); visible.addAll(notes); adapter.notifyDataSetChanged();
        empty.setText("Chưa có ghi chú\nBấm ＋ để viết note đầu tiên.");
        status.setText(notes.size() + " ghi chú · lưu trên máy");
    }

    // CASE 5: only the suggestion button waits. The list and editor remain interactive.
    private void callApi() {
        view(R.id.api).setEnabled(false);
        view(R.id.apiProgress).setVisibility(View.VISIBLE);
        TextView result = view(R.id.apiResult);
        result.setText("Đang tải gợi ý… bạn vẫn có thể cuộn và mở ghi chú.");
        String address = getPreferences(MODE_PRIVATE).getString(PREF_API, DEFAULT_API);
        api.fetch(address, result::setText, () -> {
            view(R.id.api).setEnabled(true);
            view(R.id.apiProgress).setVisibility(View.GONE);
        });
    }

    private void configureLab() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        TextView workerLabel = new TextView(this);
        workerLabel.setText("Số luồng tìm kiếm (Workers 1–16)");
        workerLabel.setTextSize(12);
        Spinner workerSpinner = new Spinner(this);
        String[] choices = new String[NoteSearch.MAX_WORKERS];
        for (int i = 0; i < choices.length; i++) choices[i] = String.valueOf(i + 1);
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        workerSpinner.setAdapter(options);
        int savedWorkers = Math.max(1, Math.min(NoteSearch.MAX_WORKERS, prefs.getInt(PREF_WORKERS, DEFAULT_WORKERS)));
        workerSpinner.setSelection(savedWorkers - 1);

        TextView limitLabel = new TextView(this);
        limitLabel.setText("Giới hạn số note khi tìm (trống = tất cả)");
        limitLabel.setTextSize(12);
        limitLabel.setPadding(0, dp(8), 0, 0);
        EditText limitInput = new EditText(this);
        limitInput.setHint("Tất cả");
        limitInput.setSingleLine();
        limitInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        limitInput.setText(prefs.getString(PREF_LIMIT, ""));

        CheckBox slowBox = new CheckBox(this);
        slowBox.setText("Lưu chậm 1,5 giây (demo khóa đồng bộ)");
        slowBox.setChecked(prefs.getBoolean(PREF_SLOW, false));

        CheckBox logBox = new CheckBox(this);
        logBox.setText("Hiện nhật ký luồng ở cuối màn hình");
        logBox.setChecked(view(R.id.demo).getVisibility() == View.VISIBLE);

        TextView apiLabel = new TextView(this);
        apiLabel.setText("Địa chỉ API gợi ý");
        apiLabel.setTextSize(12);
        apiLabel.setPadding(0, dp(8), 0, 0);
        EditText address = new EditText(this);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs.getString(PREF_API, DEFAULT_API));

        form.addView(workerLabel); form.addView(workerSpinner);
        form.addView(limitLabel); form.addView(limitInput);
        form.addView(slowBox); form.addView(logBox);
        form.addView(apiLabel); form.addView(address);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cấu hình Lab (Đa luồng)")
                .setView(scroll)
                .setNeutralButton("Mặc định", null)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                workerSpinner.setSelection(DEFAULT_WORKERS - 1);
                limitInput.setText("");
                limitInput.setError(null);
                slowBox.setChecked(false);
                address.setText(DEFAULT_API);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String limitText = limitInput.getText().toString().trim();
                if (!limitText.isEmpty()) {
                    try {
                        if (Integer.parseInt(limitText) < 1) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        limitInput.setError("Nhập số lớn hơn 0 hoặc để trống");
                        return;
                    }
                }
                String apiText = address.getText().toString().trim();
                prefs.edit()
                        .putInt(PREF_WORKERS, workerSpinner.getSelectedItemPosition() + 1)
                        .putString(PREF_LIMIT, limitText)
                        .putBoolean(PREF_SLOW, slowBox.isChecked())
                        .putString(PREF_API, apiText.isEmpty() ? DEFAULT_API : apiText)
                        .apply();
                view(R.id.demo).setVisibility(logBox.isChecked() ? View.VISIBLE : View.GONE);
                dialog.dismiss();
                toast("Đã lưu cấu hình Lab");
            });
        });
        dialog.show();
    }

    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean logVisible = view(R.id.demo).getVisibility() == View.VISIBLE;
        menu.getMenu().add(0, 1, 0, "Tải lại danh sách").setEnabled(loader == null);
        menu.getMenu().add(0, 2, 1, "Thêm 160 ghi chú mẫu").setEnabled(ready);
        menu.getMenu().add(0, 3, 2, logVisible ? "Ẩn nhật ký luồng" : "Hiện nhật ký luồng");
        menu.getMenu().add(0, 4, 3, "Cấu hình Lab (Đa luồng)");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { saveNow(); load(); }
            if (item.getItemId() == 2) new AlertDialog.Builder(this)
                    .setMessage("Thêm 160 ghi chú mẫu? Các ghi chú hiện có được giữ lại.")
                    .setNegativeButton("Hủy", null).setPositiveButton("Thêm mẫu", (d, which) -> samples()).show();
            if (item.getItemId() == 3) {
                View demo = view(R.id.demo);
                demo.setVisibility(demo.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
            if (item.getItemId() == 4) configureLab();
            return true;
        });
        menu.show();
    }

    private void samples() {
        cancelSearch(); setBusy(true); setActions(false); status.setText("Đang tạo dữ liệu mẫu…");
        store.writes.execute(() -> {
            List<Note> generated = new ArrayList<>();
            String[] topics = {"Java", "Android", "Học tập", "Công việc"};
            for (int i = 0; i < 160; i++) {
                String topic = topics[i % topics.length]; StringBuilder body = new StringBuilder();
                for (int j = 0; j < 120; j++) body.append("Ý tưởng ").append(j + 1).append(" về ").append(topic)
                        .append(": viết một chút mỗi ngày, lưu lại điều cần nhớ và ôn tập vào cuối tuần.\n");
                generated.add(new Note(UUID.randomUUID().toString(), topic + " · mẫu " + (i + 1), body.toString()));
            }
            ui(() -> { notes.addAll(generated); setActions(true); setBusy(false); changed(); saveNow(); toast("Đã thêm 160 note mẫu"); });
        });
    }

    private void log(String message) {
        String line = "[" + Thread.currentThread().getName() + "] " + message;
        Log.i("NoteLab", line);
        ui(() -> {
            if (logs.size() == 24) logs.removeFirst(); logs.addLast(line);
            journal.setText(String.join("\n", logs));
            ScrollView scroll = view(R.id.logScroll);
            scroll.post(() -> { if (alive) scroll.fullScroll(View.FOCUS_DOWN); });
        });
    }

    private void ui(Runnable work) { main.post(() -> { if (alive) work.run(); }); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void failure(String action, Exception error) {
        Log.e("NoteLab", action, error);
        String message = action + " thất bại: " + (error == null ? "không rõ nguyên nhân" : error.getMessage());
        status.setText(message); toast(message);
    }
    private void setBusy(boolean busy) { view(R.id.progress).setVisibility(busy ? View.VISIBLE : View.GONE); }
    private void setActions(boolean enabled) {
        for (int id : new int[]{R.id.add, R.id.backup, R.id.search, R.id.query, R.id.list}) view(id).setEnabled(enabled);
    }
    private static String filename(String title) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return clean.substring(0, Math.min(clean.length(), 60));
    }
    private TextWatcher watcher(Runnable work) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { work.run(); }
        };
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private <T extends View> T view(int id) { return findViewById(id); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingExport != null) {
            state.putString("exportId", pendingExport.id); state.putString("exportTitle", pendingExport.title); state.putString("exportBody", pendingExport.body);
        }
    }
    @Override protected void onPause() { super.onPause(); saveNow(); }
    @Override protected void onDestroy() {
        alive = false;
        api.close();
        if (loader != null) loader.cancel(true);
        cancelSearch(); searches.shutdownNow(); backups.shutdown(); main.removeCallbacksAndMessages(null);
        if (editor != null) editor.dismiss();
        super.onDestroy();
    }
}
