package com.yadaventerprise.phoneserver.ui.security;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class TasksActivity extends AppCompatActivity {

    private LinearLayout taskList;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tasks);
        taskList = findViewById(R.id.task_list);
        emptyText = findViewById(R.id.empty_text);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.add_button).setOnClickListener(v -> openEditor(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        taskList.removeAllViews();
        List<ScheduledTask> tasks = ScheduledTaskStore.get(this).all();
        emptyText.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (ScheduledTask task : tasks) {
            TextView row = new TextView(this);
            row.setBackgroundResource(R.drawable.bg_home_card);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setTextColor(getColor(R.color.home_text_primary));
            row.setTextSize(14);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String action = task.isBackup() ? "Project backup" : "Webhook delivery";
            String next = task.nextRunAt == 0 ? "not scheduled"
                    : format.format(new Date(task.nextRunAt));
            String status = task.enabled ? "Enabled" : "Disabled";
            if (!task.lastStatus.isEmpty()) status += " · last " + task.lastStatus;
            row.setText(task.name + "\n" + action + " · every " + task.intervalMinutes + " min"
                    + "\n" + status + " · next " + next);
            row.setTypeface(Typeface.DEFAULT);
            row.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            row.setLayoutParams(params);
            row.setOnClickListener(v -> openEditor(task.id));
            taskList.addView(row);
        }
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, TaskEditActivity.class);
        if (id != null) intent.putExtra(TaskEditActivity.EXTRA_ID, id);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
