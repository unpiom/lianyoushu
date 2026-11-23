package com.lianji.fitlog;

import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Store extends SQLiteOpenHelper {
    private final Context context;

    Store(Context context) {
        super(context, new File(context.getNoBackupFilesDir(), "journal.db").getAbsolutePath(), null, 5);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE weights (day TEXT PRIMARY KEY NOT NULL, kg REAL NOT NULL, locked INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "days INTEGER NOT NULL, minutes INTEGER NOT NULL, exercises TEXT NOT NULL)");
        db.execSQL("CREATE TABLE check_ins (day TEXT NOT NULL, plan_id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, days INTEGER NOT NULL, minutes INTEGER NOT NULL, "
                + "exercises TEXT NOT NULL, duration_seconds INTEGER, set_logs TEXT NOT NULL DEFAULT '[]', "
                + "finished_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, plan_id))");
        db.execSQL("CREATE TABLE goal (id INTEGER PRIMARY KEY CHECK (id = 1), kg REAL NOT NULL)");
        createActivityTables(db);
        for (Journal.Plan plan : defaultPlans()) {
            db.insertOrThrow("plans", null, planValues(plan));
        }
    }

    private List<Journal.Plan> defaultPlans() {
        try (InputStream stream = context.getAssets().open("example-plan.json")) {
            List<Journal.Plan> plans = PlanFiles.decode(stream);
            if (plans.isEmpty()) throw new IllegalStateException("The bundled training plan is empty");
            return plans;
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Unable to load the bundled training plan", error);
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE weights ADD COLUMN locked INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE check_ins ADD COLUMN duration_seconds INTEGER");
            db.execSQL("ALTER TABLE check_ins ADD COLUMN set_logs TEXT NOT NULL DEFAULT '[]'");
            db.execSQL("ALTER TABLE check_ins ADD COLUMN finished_at INTEGER NOT NULL DEFAULT 0");
            createActivityTables(db);
        }
        if (oldVersion < 5) removeLegacyStepData(db);
    }

    private void removeLegacyStepData(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS manual_steps");
        db.execSQL("DROP TABLE IF EXISTS wechat_steps");
        db.execSQL("DROP TABLE IF EXISTS device_steps");
        db.execSQL("DROP TABLE IF EXISTS device_step_checkpoint");
        // The old settings table only stored the step goal.
        db.execSQL("DROP TABLE IF EXISTS settings");
        context.deleteSharedPreferences("device_steps");
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) {
            notifications.deleteNotificationChannel("device_steps");
            notifications.deleteNotificationChannel("wechat_steps");
        }
    }

    private void createActivityTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE waist (day TEXT PRIMARY KEY NOT NULL, cm REAL NOT NULL)");
        db.execSQL("CREATE TABLE workout_drafts (day TEXT NOT NULL, plan_id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, days INTEGER NOT NULL, minutes INTEGER NOT NULL, exercises TEXT NOT NULL, "
                + "started_at INTEGER NOT NULL, rest_seconds INTEGER NOT NULL, rest_until INTEGER NOT NULL, "
                + "set_logs TEXT NOT NULL, PRIMARY KEY (day, plan_id))");
    }

    List<Journal.Weight> weights() {
        List<Journal.Weight> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT day, kg, locked FROM weights ORDER BY day", null)) {
            while (cursor.moveToNext()) {
                records.add(new Journal.Weight(LocalDate.parse(cursor.getString(0)), cursor.getDouble(1), cursor.getInt(2) != 0));
            }
        }
        return records;
    }

    Journal.Weight weight(LocalDate date) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT kg, locked FROM weights WHERE day = ?",
                new String[]{date.toString()})) {
            return cursor.moveToFirst() ? new Journal.Weight(date, cursor.getDouble(0), cursor.getInt(1) != 0) : null;
        }
    }

    void saveWeight(LocalDate date, double kg, boolean lock) {
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) throw new IllegalArgumentException("Future weight date");
        boolean seal = lock || date.equals(today);
        ContentValues values = new ContentValues();
        values.put("day", date.toString());
        values.put("kg", kg);
        values.put("locked", seal ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int updated = seal ? 0 : db.update("weights", values, "day = ? AND locked = 0", new String[]{date.toString()});
            if (updated == 0) db.insertOrThrow("weights", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deleteWeight(LocalDate date) {
        if (!date.isBefore(LocalDate.now()) || getWritableDatabase().delete("weights", "day = ? AND locked = 0",
                new String[]{date.toString()}) != 1) {
            throw new IllegalStateException("Weight is locked or absent");
        }
    }

    List<Journal.Measurement> waistReadings() {
        List<Journal.Measurement> readings = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT day, cm FROM waist ORDER BY day", null)) {
            while (cursor.moveToNext()) readings.add(new Journal.Measurement(LocalDate.parse(cursor.getString(0)), cursor.getDouble(1)));
        }
        return readings;
    }

    Double waist(LocalDate date) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT cm FROM waist WHERE day = ?", new String[]{date.toString()})) {
            return cursor.moveToFirst() ? cursor.getDouble(0) : null;
        }
    }

    void saveWaist(LocalDate date, double cm) {
        ContentValues values = new ContentValues();
        values.put("day", date.toString());
        values.put("cm", cm);
        insertOrReplace("waist", values);
    }

    void deleteWaist(LocalDate date) {
        getWritableDatabase().delete("waist", "day = ?", new String[]{date.toString()});
    }

    Double goal() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT kg FROM goal WHERE id = 1", null)) {
            return cursor.moveToFirst() ? cursor.getDouble(0) : null;
        }
    }

    void saveGoal(double kg) {
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("kg", kg);
        insertOrReplace("goal", values);
    }

    void deleteGoal() {
        getWritableDatabase().delete("goal", "id = 1", null);
    }

    List<Journal.Plan> plans() {
        List<Journal.Plan> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, name, days, minutes, exercises FROM plans ORDER BY id", null)) {
            while (cursor.moveToNext()) result.add(readPlan(cursor, 0));
        }
        return result;
    }

    void savePlan(Journal.Plan plan) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = planValues(plan);
        if (plan.id == 0) {
            db.insertOrThrow("plans", null, values);
        } else {
            db.update("plans", values, "id = ?", new String[]{Long.toString(plan.id)});
        }
    }

    int importPlans(List<Journal.Plan> imported) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Set<ContentValues> existing = new HashSet<>();
            for (Journal.Plan plan : plans()) existing.add(planValues(plan));
            int added = 0;
            for (Journal.Plan plan : imported) {
                ContentValues values = planValues(plan);
                if (existing.add(values)) {
                    db.insertOrThrow("plans", null, values);
                    added++;
                }
            }
            db.setTransactionSuccessful();
            return added;
        } finally {
            db.endTransaction();
        }
    }

    void resetPlans() {
        List<ContentValues> missing = new ArrayList<>();
        for (Journal.Plan plan : defaultPlans()) missing.add(planValues(plan));

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Keep unchanged plans linked to their existing check-ins and drafts.
            for (Journal.Plan plan : plans()) {
                if (!missing.remove(planValues(plan))) {
                    db.delete("plans", "id = ?", new String[]{Long.toString(plan.id)});
                }
            }
            for (ContentValues values : missing) db.insertOrThrow("plans", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deletePlan(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] args = {Long.toString(id)};
            db.delete("plans", "id = ?", args);
            db.delete("workout_drafts", "plan_id = ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    List<Journal.Session> checkIns(LocalDate start, LocalDate end) {
        List<Journal.Session> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day, plan_id, name, days, minutes, exercises, duration_seconds, set_logs FROM check_ins "
                        + "WHERE day BETWEEN ? AND ? ORDER BY day, plan_id",
                new String[]{start.toString(), end.toString()})) {
            while (cursor.moveToNext()) {
                result.add(readCheckIn(cursor));
            }
        }
        return result;
    }

    void checkIn(Journal.Session session, Long seconds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (hasDraft(session.date, session.plan.id)) throw new IllegalStateException("Workout already in progress");
            insertCheckIn(db, session, seconds, session.sets);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void insertCheckIn(SQLiteDatabase db, Journal.Session session, Long seconds, List<Journal.SetLog> sets) {
        ContentValues values = planValues(session.plan);
        values.put("day", session.date.toString());
        values.put("plan_id", session.plan.id);
        values.put("duration_seconds", seconds);
        values.put("set_logs", encodeSets(sets));
        values.put("finished_at", System.currentTimeMillis());
        db.insertOrThrow("check_ins", null, values);
    }

    Journal.WorkoutDraft draft(LocalDate date, long planId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day, plan_id, name, days, minutes, exercises, started_at, rest_seconds, rest_until, set_logs "
                        + "FROM workout_drafts WHERE day = ? AND plan_id = ?", workoutKey(date, planId))) {
            return cursor.moveToFirst() ? readDraft(cursor) : null;
        }
    }

    List<Journal.WorkoutDraft> drafts() {
        List<Journal.WorkoutDraft> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day, plan_id, name, days, minutes, exercises, started_at, rest_seconds, rest_until, set_logs "
                        + "FROM workout_drafts ORDER BY started_at DESC", null)) {
            while (cursor.moveToNext()) result.add(readDraft(cursor));
        }
        return result;
    }

    private boolean hasDraft(LocalDate date, long planId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM workout_drafts WHERE day = ? AND plan_id = ?",
                workoutKey(date, planId))) {
            return cursor.moveToFirst();
        }
    }

    void startWorkout(Journal.Session session) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor existing = db.rawQuery("SELECT 1 FROM check_ins WHERE day = ? AND plan_id = ?",
                    workoutKey(session.date, session.plan.id))) {
                if (existing.moveToFirst()) throw new IllegalStateException("Workout completed");
            }
            if (!hasDraft(session.date, session.plan.id)) {
                Journal.WorkoutDraft draft = new Journal.WorkoutDraft(session.date, session.plan, System.currentTimeMillis(),
                        Journal.WorkoutDraft.DEFAULT_REST_SECONDS, 0, new ArrayList<>());
                db.insertOrThrow("workout_drafts", null, draftValues(draft));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void saveDraft(Journal.WorkoutDraft draft) {
        int updated = getWritableDatabase().update("workout_drafts", draftValues(draft), "day = ? AND plan_id = ?",
                workoutKey(draft.date, draft.plan.id));
        if (updated != 1) throw new IllegalStateException("Workout no longer in progress");
    }

    void discardDraft(Journal.WorkoutDraft draft) {
        getWritableDatabase().delete("workout_drafts", "day = ? AND plan_id = ?", workoutKey(draft.date, draft.plan.id));
    }

    void finishWorkout(Journal.WorkoutDraft draft, long seconds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!hasDraft(draft.date, draft.plan.id)) throw new IllegalStateException("Workout no longer in progress");
            insertCheckIn(db, new Journal.Session(draft.date, draft.plan, true), seconds, draft.sets);
            discardDraft(draft);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    List<Journal.SetLog> previousSets(String exercise, LocalDate date) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day, plan_id, name, days, minutes, exercises, duration_seconds, set_logs FROM check_ins "
                        + "WHERE day <= ? AND set_logs != '[]' ORDER BY day DESC, finished_at DESC, plan_id DESC",
                new String[]{date.toString()})) {
            while (cursor.moveToNext()) {
                Journal.Session session = readCheckIn(cursor);
                List<Journal.SetLog> result = new ArrayList<>();
                for (Journal.SetLog set : session.sets) {
                    if (session.plan.exercises.get(set.exerciseIndex).name.equalsIgnoreCase(exercise)) result.add(set);
                }
                if (!result.isEmpty()) return result;
            }
            return new ArrayList<>();
        }
    }

    private static String[] workoutKey(LocalDate date, long planId) {
        return new String[]{date.toString(), Long.toString(planId)};
    }

    private static Journal.Session readCheckIn(Cursor cursor) {
        return new Journal.Session(LocalDate.parse(cursor.getString(0)), readPlan(cursor, 1), true,
                cursor.isNull(6) ? null : cursor.getLong(6), decodeSets(cursor.getString(7)));
    }

    private static Journal.WorkoutDraft readDraft(Cursor cursor) {
        return new Journal.WorkoutDraft(LocalDate.parse(cursor.getString(0)), readPlan(cursor, 1), cursor.getLong(6),
                cursor.getInt(7), cursor.getLong(8), decodeSets(cursor.getString(9)));
    }

    private static ContentValues draftValues(Journal.WorkoutDraft draft) {
        ContentValues values = planValues(draft.plan);
        values.put("day", draft.date.toString());
        values.put("plan_id", draft.plan.id);
        values.put("started_at", draft.startedAt);
        values.put("rest_seconds", draft.restSeconds);
        values.put("rest_until", draft.restUntil);
        values.put("set_logs", encodeSets(draft.sets));
        return values;
    }

    private static String encodeSets(List<Journal.SetLog> sets) {
        JSONArray encoded = new JSONArray();
        try {
            for (Journal.SetLog set : sets) {
                encoded.put(new JSONArray().put(set.exerciseIndex).put(set.reps).put(set.loadKg));
            }
            return encoded.toString();
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid set log", error);
        }
    }

    private static List<Journal.SetLog> decodeSets(String encoded) {
        List<Journal.SetLog> sets = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONArray set = array.getJSONArray(i);
                sets.add(new Journal.SetLog(set.getInt(0), set.isNull(1) ? null : set.getInt(1),
                        set.isNull(2) ? null : set.getDouble(2)));
            }
            return sets;
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid set log", error);
        }
    }

    private void insertOrReplace(String table, ContentValues values) {
        long row = getWritableDatabase().insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row == -1) throw new IllegalStateException("Journal entry could not be saved");
    }

    private static Journal.Plan readPlan(Cursor cursor, int index) {
        List<Journal.Exercise> exercises = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(cursor.getString(index + 4));
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                exercises.add(new Journal.Exercise(entry.getString("name"), entry.getInt("sets")));
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid exercise data in journal", error);
        }
        return new Journal.Plan(cursor.getLong(index), cursor.getString(index + 1),
                cursor.getInt(index + 2), cursor.getInt(index + 3), exercises);
    }

    private static ContentValues planValues(Journal.Plan plan) {
        ContentValues values = new ContentValues();
        values.put("name", plan.name);
        values.put("days", plan.days);
        values.put("minutes", plan.minutes);
        try {
            values.put("exercises", encodeExercises(plan.exercises).toString());
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid exercise", error);
        }
        return values;
    }

    static JSONArray encodeExercises(List<Journal.Exercise> exercises) throws JSONException {
        JSONArray array = new JSONArray();
        for (Journal.Exercise exercise : exercises) {
            array.put(new JSONObject().put("name", exercise.name).put("sets", exercise.sets));
        }
        return array;
    }
}
