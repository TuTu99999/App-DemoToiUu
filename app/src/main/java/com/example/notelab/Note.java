package com.example.notelab;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable: background tasks always receive a stable snapshot. */
final class Note {
    final String id, title, body;

    Note(String id, String title, String body) {
        this.id = id;
        this.title = title;
        this.body = body;
    }

    String label() { return title.trim().isEmpty() ? "Không có tiêu đề" : title.trim(); }
    String text() { return label() + "\n\n" + body; }

    JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("body", body);
    }

    static Note from(JSONObject value) throws JSONException {
        return new Note(value.getString("id"), value.getString("title"), value.getString("body"));
    }
}
