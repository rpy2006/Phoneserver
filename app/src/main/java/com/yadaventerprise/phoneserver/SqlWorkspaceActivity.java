package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.DatabaseManager;
import com.yadaventerprise.phoneserver.data.Records;

import java.util.List;

public class SqlWorkspaceActivity extends AppCompatActivity {

    private String projectId;
    private String selectedDb;
    private String selectedTable;

    private LinearLayout dbRow;
    private LinearLayout tableRow;
    private View tableScroll;
    private TextView tableInfoText;
    private EditText sqlInput;
    private TextView resultSummary;
    private TextView resultText;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sql_workspace);

        projectId = getIntent().getStringExtra(ProjectDetailActivity.EXTRA_PROJECT_ID);

        dbRow = findViewById(R.id.db_row);
        tableRow = findViewById(R.id.table_row);
        tableScroll = findViewById(R.id.table_scroll);
        tableInfoText = findViewById(R.id.table_info);
        sqlInput = findViewById(R.id.sql_input);
        resultSummary = findViewById(R.id.result_summary);
        resultText = findViewById(R.id.result_text);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.create_db_button).setOnClickListener(v -> showCreateDatabaseDialog());
        findViewById(R.id.delete_db_button).setOnClickListener(v -> confirmDeleteDatabase());
        findViewById(R.id.run_button).setOnClickListener(v -> runQuery());

        if (projectId == null) {
            Toast.makeText(this, "Missing project id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        loadDatabases();
    }

    private void loadDatabases() {
        new Thread(() -> {
            String error = null;
            List<Records.DbRecord> databases = null;
            try {
                databases = DatabaseManager.list(this, projectId);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final List<Records.DbRecord> result = databases;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                renderDatabases(result);
            });
        }).start();
    }

    private void renderDatabases(List<Records.DbRecord> databases) {
        dbRow.removeAllViews();

        TextView addChip = chip("+ New");
        addChip.setOnClickListener(v -> showCreateDatabaseDialog());
        dbRow.addView(addChip);

        if (databases.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("No databases yet.");
            hint.setTextColor(getColor(R.color.home_text_secondary));
            hint.setTextSize(13);
            hint.setPadding(dp(8), 0, 0, 0);
            dbRow.addView(hint);
            tableScroll.setVisibility(View.GONE);
            return;
        }

        if (selectedDb == null) selectedDb = databases.get(0).name;
        boolean found = false;
        for (Records.DbRecord db : databases) {
            if (db.name.equals(selectedDb)) found = true;
        }
        if (!found) selectedDb = databases.get(0).name;

        for (Records.DbRecord db : databases) {
            TextView chipView = chip(db.name + "  " + ServerStats.formatBytes(db.sizeBytes));
            boolean selected = db.name.equals(selectedDb);
            chipView.setBackgroundResource(R.drawable.bg_pill_light);
            chipView.setTextColor(getColor(selected ? R.color.home_accent : R.color.home_text_secondary));
            chipView.setOnClickListener(v -> {
                selectedDb = db.name;
                selectedTable = null;
                tableInfoText.setVisibility(View.GONE);
                tableInfoText.setText("");
                loadDatabases();
                loadTables();
            });
            dbRow.addView(chipView);
        }

        loadTables();
    }

    private void loadTables() {
        if (selectedDb == null) return;
        new Thread(() -> {
            String error = null;
            List<Records.TableInfo> tables = null;
            try {
                tables = DatabaseManager.listTables(this, projectId, selectedDb);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final List<Records.TableInfo> result = tables;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                renderTables(result);
            });
        }).start();
    }

    private void renderTables(List<Records.TableInfo> tables) {
        tableRow.removeAllViews();
        if (tables == null || tables.isEmpty()) {
            tableScroll.setVisibility(View.GONE);
            return;
        }
        tableScroll.setVisibility(View.VISIBLE);
        for (Records.TableInfo table : tables) {
            TextView chipView = chip(table.name + "  (" + table.rowCount + ")");
            boolean selected = table.name.equals(selectedTable);
            chipView.setTextColor(getColor(selected ? R.color.home_accent : R.color.home_text_secondary));
            chipView.setOnClickListener(v -> {
                selectedTable = table.name;
                sqlInput.setText("SELECT * FROM " + table.name + " LIMIT 100;");
                loadTableInfo(table.name);
                renderTables(tables);
            });
            tableRow.addView(chipView);
        }
    }

    private void loadTableInfo(String table) {
        new Thread(() -> {
            String error = null;
            Records.TableInfo info = null;
            try {
                info = DatabaseManager.tableInfo(this, projectId, selectedDb, table);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final Records.TableInfo result = info;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(result.name).append(" — ").append(result.rowCount).append(" rows\n");
                for (Records.ColumnInfo column : result.columns) {
                    sb.append("  ").append(column.name).append("  ").append(column.type);
                    if (column.primaryKey) sb.append(" PK");
                    if (column.notNull) sb.append(" NOT NULL");
                    sb.append('\n');
                }
                tableInfoText.setText(sb.toString().trim());
                tableInfoText.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private void showCreateDatabaseDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("database_name");
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New database")
                .setMessage("Letters, digits, '_', '.' and '-' only.")
                .setView(input)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            createDatabase(name);
        }));
        dialog.show();
    }

    private void createDatabase(String name) {
        new Thread(() -> {
            String error = null;
            try {
                DatabaseManager.create(this, projectId, name);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    selectedDb = name;
                    Toast.makeText(this, "Database created", Toast.LENGTH_SHORT).show();
                    loadDatabases();
                }
            });
        }).start();
    }

    private void confirmDeleteDatabase() {
        if (selectedDb == null) {
            Toast.makeText(this, "Select a database first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete database '" + selectedDb + "'?")
                .setMessage("This permanently removes the database file and all of its tables.")
                .setPositiveButton("Delete", (d, w) -> deleteDatabaseFile(selectedDb))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteDatabaseFile(String name) {
        new Thread(() -> {
            String error = null;
            try {
                DatabaseManager.delete(this, projectId, name);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    selectedDb = null;
                    selectedTable = null;
                    tableScroll.setVisibility(View.GONE);
                    tableInfoText.setVisibility(View.GONE);
                    resultText.setText("");
                    resultSummary.setText("");
                    Toast.makeText(this, "Database deleted", Toast.LENGTH_SHORT).show();
                    loadDatabases();
                }
            });
        }).start();
    }

    private void runQuery() {
        if (selectedDb == null) {
            Toast.makeText(this, "Select a database first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String sql = sqlInput.getText().toString();
        if (sql.trim().isEmpty()) {
            Toast.makeText(this, "Enter a SQL statement", Toast.LENGTH_SHORT).show();
            return;
        }
        resultSummary.setText("Running...");
        resultText.setText("");
        new Thread(() -> {
            String error = null;
            Records.QueryResult result = null;
            try {
                result = DatabaseManager.execute(this, projectId, selectedDb, sql, null,
                        100, 0, 5000L, true);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final Records.QueryResult queryResult = result;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    resultSummary.setText("Error");
                    resultText.setText(message);
                    return;
                }
                renderResult(queryResult);
            });
        }).start();
    }

    private void renderResult(Records.QueryResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(result.isSelect ? "Query" : "Write")
                .append(" · ").append(result.keyword)
                .append(" · ").append(result.durationMs).append(" ms");
        if (result.isSelect) {
            summary.append(" · ").append(result.rows.size()).append(" rows");
            if (result.truncated) summary.append(" (truncated)");
        } else {
            summary.append(" · ").append(result.rowsAffected).append(" rows affected");
        }
        resultSummary.setText(summary.toString());

        if (!result.isSelect) {
            resultText.setText("Statement executed successfully.");
            return;
        }
        if (result.columns.isEmpty()) {
            resultText.setText("(no columns)");
            return;
        }

        int[] widths = new int[result.columns.size()];
        for (int i = 0; i < result.columns.size(); i++) {
            widths[i] = result.columns.get(i).length();
        }
        for (List<Object> row : result.rows) {
            for (int i = 0; i < row.size() && i < widths.length; i++) {
                widths[i] = Math.max(widths[i], cell(row.get(i)).length());
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < result.columns.size(); i++) {
            if (i > 0) out.append(" | ");
            out.append(pad(result.columns.get(i), widths[i]));
        }
        out.append('\n');
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) out.append("-+-");
            for (int j = 0; j < widths[i]; j++) out.append('-');
        }
        out.append('\n');
        if (result.rows.isEmpty()) {
            out.append("(no rows)");
        } else {
            for (List<Object> row : result.rows) {
                for (int i = 0; i < result.columns.size(); i++) {
                    if (i > 0) out.append(" | ");
                    String value = i < row.size() ? cell(row.get(i)) : "";
                    out.append(pad(value, widths[i]));
                }
                out.append('\n');
            }
        }
        resultText.setText(out.toString());
    }

    private String cell(Object value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value;
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private TextView chip(String text) {
        TextView chipView = new TextView(this);
        chipView.setText(text);
        chipView.setBackgroundResource(R.drawable.bg_pill_light);
        chipView.setTextColor(getColor(R.color.home_accent));
        chipView.setTextSize(13);
        chipView.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chipView.setLayoutParams(params);
        return chipView;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }
}
