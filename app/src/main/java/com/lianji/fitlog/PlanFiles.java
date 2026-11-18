package com.lianji.fitlog;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class PlanFiles {
    private static final int IMPORT = 43;
    private static final int EXPORT = 44;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String FORMAT = "com.lianji.fitlog.plans";
    private static final int VERSION = 1;

    private final MainActivity activity;
    private boolean busy;

    PlanFiles(MainActivity activity) {
        this.activity = activity;
    }

    void openImport() {
        pick(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"), IMPORT);
    }

    void openExport() {
        pick(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "练有数-训练计划-" + LocalDate.now() + ".json"), EXPORT);
    }

    private void pick(Intent intent, int request) {
        if (busy) return;
        try {
            activity.startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), request);
        } catch (ActivityNotFoundException error) {
            show("无法打开系统文件选择器。");
        }
    }

    void onActivityResult(int request, int result, Intent data) {
        if ((request != IMPORT && request != EXPORT) || result != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            show("无法打开所选文件。");
            return;
        }
        busy = true;
        Context context = activity.getApplicationContext();
        new Thread(() -> {
            String message;
            try (Store store = new Store(context)) {
                if (request == EXPORT) {
                    List<Journal.Plan> plans = store.plans();
                    byte[] contents = encode(plans);
                    try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IOException("Cannot open export document");
                        stream.write(contents);
                    }
                    message = "已导出 " + plans.size() + " 个训练计划。";
                } else {
                    List<Journal.Plan> plans;
                    try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new IOException("Cannot open import document");
                        plans = decode(stream);
                    }
                    int added = store.importPlans(plans);
                    int duplicates = plans.size() - added;
                    message = "已导入 " + added + " 个训练计划。";
                    if (duplicates > 0) message += " 已跳过 " + duplicates + " 个重复计划。";
                }
            } catch (JSONException error) {
                message = "文件格式或内容无效，请选择练有数导出的训练计划 JSON 文件。";
            } catch (IOException | RuntimeException error) {
                Log.e("Lianji", "Plan file operation failed", error);
                message = request == IMPORT ? "无法读取或保存训练计划，请检查所选文件后重试。"
                        : "无法导出训练计划，请检查存储空间后重试。";
            }
            String outcome = message;
            activity.runOnUiThread(() -> {
                busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (request == IMPORT) activity.refresh();
                show(outcome);
            });
        }, "plan-file").start();
    }

    private void show(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    static byte[] encode(List<Journal.Plan> plans) throws JSONException, IOException {
        JSONArray entries = new JSONArray();
        for (Journal.Plan plan : plans) {
            entries.put(new JSONObject().put("name", plan.name).put("days", plan.days)
                    .put("minutes", plan.minutes).put("exercises", Store.encodeExercises(plan.exercises)));
        }
        byte[] bytes = (new JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("plans", entries).toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Plan file exceeds size limit");
        return bytes;
    }

    static List<Journal.Plan> decode(InputStream stream) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (read > MAX_BYTES - bytes.size()) throw new IOException("Plan file exceeds size limit");
            bytes.write(buffer, 0, read);
        }
        String contents = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        if (contents.startsWith("\ufeff")) contents = contents.substring(1);
        try (JsonReader reader = new JsonReader(new StringReader(contents))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new JSONException("Expected a plan file object");
            validateJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("Unexpected content after plan file");
        } catch (IOException error) {
            throw new JSONException("Invalid plan file JSON");
        }
        JSONObject file = new JSONObject(contents);
        if (!FORMAT.equals(file.get("format")) || integer(file, "version", VERSION, VERSION) != VERSION) {
            throw new JSONException("Unknown plan file format");
        }

        JSONArray entries = file.getJSONArray("plans");
        List<Journal.Plan> plans = new ArrayList<>(entries.length());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            JSONArray items = entry.getJSONArray("exercises");
            if (items.length() == 0) throw new JSONException("Empty exercise list");
            List<Journal.Exercise> exercises = new ArrayList<>(items.length());
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                exercises.add(new Journal.Exercise(name(item), integer(item, "sets", 1, 999)));
            }
            plans.add(new Journal.Plan(0, name(entry), integer(entry, "days", 1, 127),
                    integer(entry, "minutes", 1, 1440), exercises));
        }
        return plans;
    }

    private static void validateJson(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 8) throw new JSONException("Plan file nesting is too deep");
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            while (reader.hasNext()) {
                reader.nextName();
                validateJson(reader, depth + 1);
            }
            reader.endObject();
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) validateJson(reader, depth + 1);
            reader.endArray();
        } else {
            reader.skipValue();
        }
    }

    private static String name(JSONObject item) throws JSONException {
        Object value = item.get("name");
        if (!(value instanceof String)) throw new JSONException("Missing name");
        String name = ((String) value).trim();
        if (name.isEmpty() || name.length() > 40) throw new JSONException("Invalid name length");
        return name;
    }

    private static int integer(JSONObject item, String key, int min, int max) throws JSONException {
        Object value = item.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new JSONException("Invalid " + key);
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new JSONException("Out-of-range " + key);
        return (int) number;
    }
}
