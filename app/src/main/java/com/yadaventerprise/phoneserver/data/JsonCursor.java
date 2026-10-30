package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWindow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JsonCursor implements Cursor {
    private List<String> columns = new ArrayList<>();
    private List<Object[]> rows = new ArrayList<>();
    private int position = -1;
    
    public JsonCursor(Context ctx, String sql, String[] selectionArgs) {
        try {
            if (sql == null) return;
            String lower = sql.toLowerCase().trim();
            JSONObject data = SystemStore.getData(ctx);
            
            if (lower.contains("from projects")) {
                JSONArray arr = SystemStore.getArray(data, "projects");
                columns.add("id"); columns.add("name"); columns.add("description");
                columns.add("file_quota_bytes"); columns.add("max_upload_bytes");
                columns.add("created_at"); columns.add("updated_at");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("name");
                    row[2] = r.optString("description", "");
                    row[3] = r.has("file_quota_bytes") ? r.get("file_quota_bytes") : 0;
                    row[4] = r.has("max_upload_bytes") ? r.get("max_upload_bytes") : 0;
                    row[5] = r.has("created_at") ? r.get("created_at") : 0;
                    row[6] = r.has("updated_at") ? r.get("updated_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("from project_databases")) {
                JSONArray arr = SystemStore.getArray(data, "project_databases");
                columns.add("id"); columns.add("project_id"); columns.add("name"); 
                columns.add("file_name"); columns.add("size_bytes");
                columns.add("created_at"); columns.add("updated_at");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    // filter if args
                    if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                        if (!r.optString("project_id").equals(selectionArgs[0])) continue;
                    }
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("project_id");
                    row[2] = r.optString("name");
                    row[3] = r.optString("file_name");
                    row[4] = r.has("size_bytes") ? r.get("size_bytes") : 0;
                    row[5] = r.has("created_at") ? r.get("created_at") : 0;
                    row[6] = r.has("updated_at") ? r.get("updated_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("from files")) {
                JSONArray arr = SystemStore.getArray(data, "files");
                columns.add("id"); columns.add("project_id"); columns.add("original_name");
                columns.add("storage_key"); columns.add("folder_path"); columns.add("mime_type");
                columns.add("size_bytes"); columns.add("checksum"); columns.add("description");
                columns.add("tags"); columns.add("created_at"); columns.add("updated_at");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (selectionArgs != null && selectionArgs.length > 0) {
                        boolean match = true;
                        if (lower.contains("project_id=?")) {
                            if (selectionArgs.length > 0 && !r.optString("project_id").equals(selectionArgs[0])) {
                                match = false;
                            }
                        }
                        if (!match && lower.contains("storage_key=?")) {
                            match = r.optString("storage_key").equals(selectionArgs[0]);
                        }
                        if (!match && lower.contains("id=?")) {
                            match = r.optString("id").equals(selectionArgs[0]);
                        }
                        if (!match) continue;
                    }
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("project_id");
                    row[2] = r.optString("original_name");
                    row[3] = r.optString("storage_key");
                    row[4] = r.optString("folder_path", "");
                    row[5] = r.optString("mime_type", "application/octet-stream");
                    row[6] = r.has("size_bytes") ? r.get("size_bytes") : 0;
                    row[7] = r.optString("checksum", null);
                    row[8] = r.optString("description", "");
                    row[9] = r.optString("tags", "");
                    row[10] = r.has("created_at") ? r.get("created_at") : 0;
                    row[11] = r.has("updated_at") ? r.get("updated_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("from folders")) {
                JSONArray arr = SystemStore.getArray(data, "folders");
                columns.add("id"); columns.add("project_id"); columns.add("path"); columns.add("created_at");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                        if (!r.optString("project_id").equals(selectionArgs[0])) continue;
                    }
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("project_id");
                    row[2] = r.optString("path");
                    row[3] = r.has("created_at") ? r.get("created_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("from api_tokens")) {
                JSONArray arr = SystemStore.getArray(data, "api_tokens");
                columns.add("id"); columns.add("name"); columns.add("project_id");
                columns.add("can_write"); columns.add("created_at"); columns.add("last_used_at"); columns.add("revoked");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (selectionArgs != null && selectionArgs.length > 0) {
                        if (lower.contains("token_hash=?")) {
                            if (!r.optString("token_hash").equals(selectionArgs[0])) continue;
                        }
                    }
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("name");
                    row[2] = r.has("project_id") && !r.isNull("project_id") ? r.optString("project_id") : null;
                    row[3] = r.optInt("can_write", 1);
                    row[4] = r.has("created_at") ? r.get("created_at") : 0;
                    row[5] = r.has("last_used_at") ? r.get("last_used_at") : 0;
                    row[6] = r.optInt("revoked", 0);
                    rows.add(row);
                }
            } else if (lower.contains("from backups")) {
                JSONArray arr = SystemStore.getArray(data, "backups");
                columns.add("id"); columns.add("project_id"); columns.add("file_name");
                columns.add("size_bytes"); columns.add("includes_files"); columns.add("valid");
                columns.add("note"); columns.add("created_at");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                        if (!r.optString("project_id").equals(selectionArgs[0])) continue;
                    }
                    Object[] row = new Object[columns.size()];
                    row[0] = r.optString("id");
                    row[1] = r.optString("project_id");
                    row[2] = r.optString("file_name");
                    row[3] = r.has("size_bytes") ? r.get("size_bytes") : 0;
                    row[4] = r.optInt("includes_files", 1);
                    row[5] = r.optInt("valid", 1);
                    row[6] = r.optString("note", "");
                    row[7] = r.has("created_at") ? r.get("created_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("from operations")) {
                JSONArray arr = SystemStore.getArray(data, "operations");
                columns.add("id"); columns.add("project_id"); columns.add("action");
                columns.add("detail"); columns.add("created_at");
                // sort by id desc as in original
                List<JSONObject> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
                // simple sort
                list.sort((a,b) -> {
                    try {
                        long ida = a.has("id") ? a.getLong("id") : 0;
                        long idb = b.has("id") ? b.getLong("id") : 0;
                        return Long.compare(idb, ida);
                    } catch (Exception e) { return 0; }
                });
                // limit if needed
                if (lower.contains("limit 500")) {
                    if (list.size() > 500) list = list.subList(0, 500);
                }
                for (JSONObject r : list) {
                    Object[] row = new Object[columns.size()];
                    row[0] = r.has("id") ? r.get("id") : 0;
                    row[1] = r.has("project_id") && !r.isNull("project_id") ? r.optString("project_id") : null;
                    row[2] = r.optString("action");
                    row[3] = r.optString("detail", "");
                    row[4] = r.has("created_at") ? r.get("created_at") : 0;
                    rows.add(row);
                }
            } else if (lower.contains("count(*)")) {
                columns.add("count(*)");
                Object[] row = new Object[1];
                if (lower.contains("from projects")) row[0] = SystemStore.getArray(data, "projects").length();
                else if (lower.contains("from project_databases")) {
                    JSONArray arr = SystemStore.getArray(data, "project_databases");
                    int c = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                            if (arr.getJSONObject(i).optString("project_id").equals(selectionArgs[0])) c++;
                        } else c++;
                    }
                    row[0] = selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?") ? countBy(data, "project_databases", "project_id", selectionArgs[0]) : arr.length();
                } else if (lower.contains("from files")) {
                    row[0] = selectionArgs != null && selectionArgs.length > 0 ? countBy(data, "files", "project_id", selectionArgs[0]) : SystemStore.getArray(data, "files").length();
                } else if (lower.contains("from folders")) {
                    row[0] = selectionArgs != null && selectionArgs.length > 0 ? countBy(data, "folders", "project_id", selectionArgs[0]) : SystemStore.getArray(data, "folders").length();
                } else if (lower.contains("from backups")) {
                    row[0] = selectionArgs != null && selectionArgs.length > 0 ? countBy(data, "backups", "project_id", selectionArgs[0]) : SystemStore.getArray(data, "backups").length();
                } else {
                    row[0] = 0;
                }
                rows.add(row);
            } else if (lower.contains("sum")) {
                columns.add("sum");
                Object[] row = new Object[1];
                if (lower.contains("from files")) {
                    JSONArray arr = SystemStore.getArray(data, "files");
                    long sum = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                            if (!r.optString("project_id").equals(selectionArgs[0])) continue;
                        }
                        sum += r.optLong("size_bytes", 0);
                    }
                    row[0] = sum;
                } else if (lower.contains("from project_databases")) {
                    JSONArray arr = SystemStore.getArray(data, "project_databases");
                    long sum = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        sum += arr.getJSONObject(i).optLong("size_bytes", 0);
                    }
                    row[0] = sum;
                } else if (lower.contains("from backups")) {
                    JSONArray arr = SystemStore.getArray(data, "backups");
                    long sum = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        if (selectionArgs != null && selectionArgs.length > 0 && lower.contains("project_id=?")) {
                            if (!arr.getJSONObject(i).optString("project_id").equals(selectionArgs[0])) continue;
                        }
                        sum += arr.getJSONObject(i).optLong("size_bytes", 0);
                    }
                    row[0] = sum;
                } else {
                    row[0] = 0;
                }
                rows.add(row);
            } else {
                // try to handle simple selects
            }
        } catch (Exception e) {
            // empty
        }
    }
    
    private int countBy(JSONObject data, String table, String key, String value) throws Exception {
        JSONArray arr = SystemStore.getArray(data, table);
        int c = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (arr.getJSONObject(i).optString(key).equals(value)) c++;
        }
        return c;
    }
    
    public int getCount() { return rows.size(); }
    public int getColumnCount() { return columns.size(); }
    public String getColumnName(int columnIndex) { return columnIndex < columns.size() ? columns.get(columnIndex) : ""; }
    public int getColumnIndex(String columnName) { 
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).equals(columnName)) return i;
        return -1;
    }
    public boolean moveToFirst() { position = rows.isEmpty() ? -1 : 0; return position >= 0; }
    public boolean moveToNext() { if (position < rows.size()-1) { position++; return true; } return false; }
    public boolean moveToLast() { if (rows.isEmpty()) { position = -1; return false; } position = rows.size()-1; return true; }
    public boolean move(int offset) { int newPos = position + offset; if (newPos < 0 || newPos >= rows.size()) { position = newPos < 0 ? -1 : rows.size(); return false; } position = newPos; return true; }
    public boolean moveToPrevious() { if (position > 0) { position--; return true; } position = -1; return false; }
    public boolean moveToPosition(int position) { if (position >=0 && position < rows.size()) { this.position = position; return true; } return false; }
    public int getPosition() { return position; }
    public boolean isFirst() { return position == 0; }
    public boolean isLast() { return position == rows.size()-1; }
    public boolean isBeforeFirst() { return position == -1; }
    public boolean isAfterLast() { return position >= rows.size(); }
    public int getType(int columnIndex) { 
        if (position < 0 || position >= rows.size() || columnIndex < 0 || columnIndex >= rows.get(position).length) return FIELD_TYPE_NULL;
        Object v = rows.get(position)[columnIndex];
        if (v == null || v == JSONObject.NULL) return FIELD_TYPE_NULL;
        if (v instanceof Integer || v instanceof Long) return FIELD_TYPE_INTEGER;
        if (v instanceof Float || v instanceof Double) return FIELD_TYPE_FLOAT;
        return FIELD_TYPE_STRING;
    }
    public String getString(int columnIndex) { 
        if (position < 0 || position >= rows.size() || columnIndex < 0 || columnIndex >= rows.get(position).length) return null;
        Object v = rows.get(position)[columnIndex];
        if (v == null || v == JSONObject.NULL) return null;
        return String.valueOf(v);
    }
    public short getShort(int columnIndex) { long v = getLong(columnIndex); return (short)v; }
    public int getInt(int columnIndex) { long v = getLong(columnIndex); return (int)v; }
    public long getLong(int columnIndex) { 
        if (position < 0 || position >= rows.size() || columnIndex < 0 || columnIndex >= rows.get(position).length) return 0;
        Object v = rows.get(position)[columnIndex];
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number)v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    public float getFloat(int columnIndex) { double v = getDouble(columnIndex); return (float)v; }
    public double getDouble(int columnIndex) { 
        if (position < 0 || position >= rows.size() || columnIndex < 0 || columnIndex >= rows.get(position).length) return 0;
        Object v = rows.get(position)[columnIndex];
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number)v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    public byte[] getBlob(int columnIndex) { return null; }
    public boolean isNull(int columnIndex) { 
        if (position < 0 || position >= rows.size()) return true;
        if (columnIndex < 0 || columnIndex >= rows.get(position).length) return true;
        Object v = rows.get(position)[columnIndex];
        return v == null || v == JSONObject.NULL;
    }
    public void close() {}
    public boolean isClosed() { return false; }
    public void registerContentObserver(android.database.ContentObserver observer) {}
    public void unregisterContentObserver(android.database.ContentObserver observer) {}
    public void setNotificationUri(android.content.ContentResolver cr, android.net.Uri uri) {}
    public android.net.Uri getNotificationUri() { return null; }
    public boolean getWantsAllOnMoveCalls() { return false; }
    public void setExtras(android.os.Bundle extras) {}
    public android.os.Bundle getExtras() { return android.os.Bundle.EMPTY; }
    public android.os.Bundle respond(android.os.Bundle extras) { return android.os.Bundle.EMPTY; }
    public CursorWindow getWindow() { return null; }
    public void copyStringToBuffer(int columnIndex, android.database.CharArrayBuffer buffer) {}
    public void registerDataSetObserver(android.database.DataSetObserver observer) {}
    public void unregisterDataSetObserver(android.database.DataSetObserver observer) {}
    public boolean requery() { return false; }
    public void deactivate() {}
    public String[] getColumnNames() { String[] arr = new String[columns.size()]; return columns.toArray(arr); }
    public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException { int idx = getColumnIndex(columnName); if (idx < 0) throw new IllegalArgumentException("column"); return idx; }
}
