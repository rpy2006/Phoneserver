package com.yadaventerprise.phoneserver.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonDatabaseWrapper {
    private final Context ctx;
    
    public JsonDatabaseWrapper(android.content.Context context) {
        this.ctx = context;
    }
    
    public long insert(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public long insertOrThrow(String table, String nullColumnHack, ContentValues values) throws RuntimeException {
        try {
            long res = insertWithOnConflict(table, nullColumnHack, values, 0);
            if (res == -1) throw new RuntimeException();
            return res;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) {
        try {
            JSONObject data = SystemStore.getData(ctx);
            JSONArray arr = SystemStore.getArray(data, table);
            JSONObject row = new JSONObject();
            if (values != null) {
                for (Map.Entry<String, Object> entry : values.valueSet()) {
                    Object v = entry.getValue();
                    if (v instanceof byte[]) {
                        row.put(entry.getKey(), android.util.Base64.encodeToString((byte[])v, android.util.Base64.NO_WRAP));
                    } else if (v == null) {
                        row.put(entry.getKey(), JSONObject.NULL);
                    } else {
                        row.put(entry.getKey(), v);
                    }
                }
            }
            // check constraints
            if (table.equals("projects") && conflictAlgorithm != 4) {
                String name = values != null ? values.getAsString("name") : null;
                if (name != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject existing = arr.getJSONObject(i);
                        if (name.equals(existing.optString("name"))) {
                            throw new RuntimeException();
                        }
                    }
                }
            }
            if (table.equals("api_tokens") && conflictAlgorithm != 4) {
                // handle
            }
            arr.put(row);
            SystemStore.saveData(ctx, data);
            return arr.length();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            return -1;
        }
    }
    
    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
        try {
            JSONObject data = SystemStore.getData(ctx);
            JSONArray arr = SystemStore.getArray(data, table);
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (matches(row, whereClause, whereArgs)) {
                    if (values != null) {
                        for (Map.Entry<String, Object> entry : values.valueSet()) {
                            Object v = entry.getValue();
                            if (v == null) {
                                row.put(entry.getKey(), JSONObject.NULL);
                            } else {
                                row.put(entry.getKey(), v);
                            }
                        }
                    }
                    count++;
                }
            }
            SystemStore.saveData(ctx, data);
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
    
    public int delete(String table, String whereClause, String[] whereArgs) {
        try {
            JSONObject data = SystemStore.getData(ctx);
            JSONArray arr = SystemStore.getArray(data, table);
            JSONArray newArr = new JSONArray();
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (matches(row, whereClause, whereArgs)) {
                    count++;
                    // handle cascades for foreign keys (simple)
                    if (table.equals("projects")) {
                        String pid = row.optString("id");
                        // cascade - will be handled by caller or simple cleanup
                    }
                    continue;
                }
                newArr.put(row);
            }
            data.put(table, newArr);
            SystemStore.saveData(ctx, data);
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private boolean matches(JSONObject row, String whereClause, String[] whereArgs) {
        if (whereClause == null || whereClause.trim().isEmpty()) return true;
        try {
            // simple patterns
            if (whereClause.contains("id=?") && whereArgs != null && whereArgs.length >= 1) {
                return row.optString("id").equals(whereArgs[0]) || String.valueOf(row.opt("id")).equals(whereArgs[0]);
            }
            if (whereClause.contains("project_id=?") && whereArgs != null && whereArgs.length >= 1) {
                return row.optString("project_id").equals(whereArgs[0]);
            }
            if (whereClause.contains("token_hash=?") && whereArgs != null && whereArgs.length >= 1) {
                return row.optString("token_hash").equals(whereArgs[0]);
            }
            if (whereClause.contains("project_id=? AND path=?") && whereArgs != null && whereArgs.length >= 2) {
                return row.optString("project_id").equals(whereArgs[0]) && row.optString("path").equals(whereArgs[1]);
            }
            if (whereClause.contains("name=?") && whereArgs != null && whereArgs.length >= 1) {
                return row.optString("name").equals(whereArgs[0]);
            }
        } catch (Exception ignored) {}
        // fallback - if no args match specific patterns, be permissive for some cases
        if (whereArgs == null || whereArgs.length == 0) return true;
        // try to match first arg
        try {
            if (whereClause.contains("=?")) {
                // simple check
                for (String key : new String[]{"id", "project_id", "token_hash", "name", "storage_key"}) {
                    if (whereClause.contains(key + "=?")) {
                        Object v = row.opt(key);
                        return v != null && String.valueOf(v).equals(whereArgs[0]);
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
    
    public Cursor rawQuery(String sql, String[] selectionArgs) {
        return new JsonCursor(ctx, sql, selectionArgs);
    }
    
    public void execSQL(String sql) {
        execSQL(sql, null);
    }
    
    public void execSQL(String sql, Object[] bindArgs) {
        try {
            // handle simple operations if needed
        } catch (Exception ignored) {}
    }
    
    public void beginTransaction() {}
    public void endTransaction() {}
    public void setTransactionSuccessful() {}
}
